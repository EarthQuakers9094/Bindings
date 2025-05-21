package bindings

import com.pathplanner.lib.auto.NamedCommands
import edu.wpi.first.util.ErrorMessages
import edu.wpi.first.util.sendable.SendableBuilder
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.Filesystem
import edu.wpi.first.wpilibj.Timer
import edu.wpi.first.wpilibj.smartdashboard.SendableChooser
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard
import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.Commands
import edu.wpi.first.wpilibj2.command.InstantCommand
import edu.wpi.first.wpilibj2.command.button.CommandGenericHID
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.function.Supplier
import kotlin.math.max
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.reflect.typeOf


// copied from wpilib because they are deprecating a constructor i need
class MyProxyCommand : Command {
    private val m_supplier: Supplier<Command>
    private var m_command: Command? = null

    constructor(supplier: Supplier<Command>) {
        m_supplier = ErrorMessages.requireNonNullParam(supplier, "supplier", "ProxyCommand")
    }

//    constructor(command: Command) {
//        val nullCheckedCommand = ErrorMessages.requireNonNullParam(command, "command", "ProxyCommand")
//        m_supplier = Supplier { nullCheckedCommand }
//        name = "Proxy(" + nullCheckedCommand.name + ")"
//    }

    override fun initialize() {
        m_command = m_supplier.get()
        m_command!!.schedule()
    }

    override fun end(interrupted: Boolean) {
        if (interrupted) {
            m_command!!.cancel()
        }
        m_command = null
    }

    override fun execute() {}

    override fun isFinished(): Boolean {
        return m_command == null || !m_command!!.isScheduled
    }

    /**
     * Whether the given command should run when the robot is disabled. Override to return true if the
     * command should run when disabled.
     *
     * @return true. Otherwise, this proxy would cancel commands that do run when disabled.
     */
    override fun runsWhenDisabled(): Boolean {
        return true
    }

    override fun initSendable(builder: SendableBuilder) {
        super.initSendable(builder)
        builder.addStringProperty(
            "proxied", { if (m_command == null) "null" else m_command!!.name }, null
        )
    }
}

fun myGetJsonObject(e: JsonElement?): JsonObject? {
    return when (e) {
        is JsonObject -> e.jsonObject
        else -> null
    }
}

@Serializable
data class SaveData(
    val constants: JsonElement
)

@Serializable
data class Profile(
    val command_to_bindings: HashMap<String, List<Binding>>,
    val controllers: List<JsonElement>, // handling raw
    val controller_names: List<String>,
    val constants: JsonElement,
) {
    val controller_sensitivities = controllers.map {
        val t = (myGetJsonObject(it)?.get("XBox")?.jsonObject?.get("sensitivity")?.jsonPrimitive?.double)

        if (t == null) {
            0.5
        } else {
            t
        }
    }
    val controller_buttons = controllers.map {
        when (it) {
        is JsonObject -> {
            if (it.keys.contains("XBox")) {
                Pair(10, 6)
            } else {
                Pair(it["Generic"]!!.jsonObject["buttons"]!!.jsonPrimitive.int, 0)
            }
        }
        else -> {
            Pair(0,0)
        }
    }
    }
}

@Serializable
enum class RunWhen {
    OnTrue,
    OnFalse,
    WhileTrue,
    WhileFalse,
}

@Serializable
enum class ButtonLocation {
    Button,
    Pov,
    Analog
}

@Serializable
data class Button(val button: Int, val location: ButtonLocation)

@Serializable
data class Binding(
    val controller: Int,
    val button: Button,
    val during: RunWhen
)

data class Buttons(
    val pov: MutableList<MutableList<Command?>>,
    val regular: MutableList<MutableList<Command?>>,
    val analog: MutableList<MutableList<Command?>>,
)

public class Constant<Value : Any>(private var value: Value, private val c: KClass<Value>) {
    val listeners = CopyOnWriteArrayList<(v: Value) -> Unit>();

    public fun addListener(ev: (v: Value) -> Unit) {
        listeners.add(ev);
    }

    public fun updateValueObject(v: Object) {
        this.updateValue(c.cast(v))
    }

    public fun updateValue(v: Value) {
        value = v;

        for (l in listeners) {
            l(v)
        }
    }

    public fun getValue(): Value {
        return value
    }
}

fun getOrDriverDefault(constants: JsonElement): JsonPrimitive? {
    return when (constants) {
        is JsonObject -> {
            val a = constants.jsonObject["default"];

            if (a == null) {
                null
            } else {
                tryGetPrimative(a)
            }
        }
        is JsonPrimitive -> {
            constants.jsonPrimitive
        }
        else -> {
            DriverStation.reportError("$constants is not Driver (with default) or normal value", true);
            null
        }
    }
}

fun tryGetPrimative(constants: JsonElement): JsonPrimitive? {
    return when (constants) {
        is JsonPrimitive -> {
            constants.jsonPrimitive;
        }
        else -> {
            DriverStation.reportError("$constants is not a json primitive", true);
            null
        }
    }
}

fun <T : Any> makeConstants(c: KClass<T>, constants: JsonElement): Constant<T>? {
    val a = c.typeParameters.get(0) as? KClass<T>;

    if (a == null) {
        return null;
    }

    val r = makeConstantsClass(a, constants);

    if (r == null) {
        return null;
    }

    return Constant(r, a)
}

fun<T: Any> makeConstantsClass(c: KClass<T>, constants: JsonElement): T? {
    val a = when (c) {
        Int::class -> {
            DriverStation.reportWarning("making int? $c", false);
            val i: Int = getOrDriverDefault(constants)?.int!!;
            return c.cast(i)
        }
        Double::class -> {
            getOrDriverDefault(constants)?.double
        }
        String::class -> {
            getOrDriverDefault(constants)?.content
        }
       Constant::class-> {
           val a = c as KClass<Constant<*>>;

           val typeArgs = c.typeParameters.firstOrNull();

           val b = typeArgs as? KClass<*>;

           return c.cast(makeConstants(a, constants));
       }
        else -> {
            val cons = c.constructors;

            val name = c.simpleName;

            if (cons.size == 0) {
                DriverStation.reportError("$name does not have any visible constructors (crashing now)", false);
            }

            DriverStation.reportWarning("number of constructors: ${cons.size}", false)

            val con = cons.first();

            val map = myGetJsonObject(myGetJsonObject(constants)?.get("map"));

            DriverStation.reportWarning("fields ${con.parameters.size}", false);

            con.call(*(con.parameters.map {
                val a = map?.get("${it.name}");

                DriverStation.reportWarning("map ${map}", false);

                DriverStation.reportError("making ${it.name}", false);
                DriverStation.reportWarning("value ${it}", false)

                DriverStation.reportWarning("parameter: ${a}, ${it.type}", false)

                val res = if (a == null) {
                    null
                } else {
                    makeConstantsClass(it.type.classifier as KClass<*>, a)
                };

                DriverStation.reportWarning("result ${res}", false);

                res
            }.toTypedArray()))
        }
    };

    return c.cast(a)
}

fun update_constants(
    constants: Object,
    global: JsonElement?,
    driver1: JsonElement?,
    driver2: JsonElement?): Object? {
    return when (constants) {
        is Int -> {
            getOrDriver(global, driver1, driver2)?.int
        }
        is Double -> {
            getOrDriver(global, driver1, driver2)?.double
        }
        is String -> {
            getOrDriver(global, driver1, driver2)?.content
        }
        is Constant<*> -> {
            val c = constants.getValue()!!.javaClass;

            val v = update_constants(constants.getValue() as Object, global, driver1, driver2);

            if (v == null) {
                null
            } else {
                constants.updateValueObject(v);
                constants as Object
            }
        }
        else -> {
            val global = myGetJsonObject(myGetJsonObject(global)?.get("map"));
            val driver1 = myGetJsonObject(myGetJsonObject(driver1)?.get("map"));
            val driver2 = myGetJsonObject(myGetJsonObject(driver1)?.get("map"));

            for (field in constants::class.java.fields) {
                DriverStation.reportWarning("accessing $field from $constants", false);

                val v = field.get(constants);

                val name = field.name;

                val o = update_constants(v as Object, global?.get(name), driver1?.get(name), driver2?.get(name))

                if (o == null) {
                    return null
                } else {
                    field.set(constants, o)
                }
            }

            constants
        }
    } as Object?
}

fun getAsPrimitive(a: JsonElement?): JsonPrimitive? {
    return when (a) {
        is JsonPrimitive -> {
            a.jsonPrimitive
        }
        else -> {
            null
        }
    }
}

fun getOrDriver(
    global: JsonElement?,
    driver1: JsonElement?,
    driver2: JsonElement?,
): JsonPrimitive? {
    return when (global) {
        is JsonObject -> {
            getAsPrimitive(driver1) ?: getAsPrimitive(driver2)
        }
        is JsonPrimitive ->{
            global.jsonPrimitive
        }
        else -> {
            DriverStation.reportError("not primitive or driver $global", true);
            null
        }
    };
}

// pass the driver and operator in here to lock them
// or pass in null to display a chooser to let them
// be chosen at runtime (best during testing)
// it's probably a good idea to lock the driver at competitions
// to remove room for mistakes.
//
// driver and operator can be unlocked by calling unlock_drivers
// or pressing the unlock drivers button on SmartDashboard
// if needed during competition.
//
// bindings can be reloaded by calling reset bindings
// during the competition. it's suggest to call this at the start of teleop
class Bindings<C: Any>(private val driver_lock: String?, private val operator_lock: String?, private val c: Class<C>) {
    val usedBindings: HashSet<Binding>;
    var bindings: MutableList<Buttons>;
    var controllers: MutableList<CommandGenericHID?>
    var controller_sensitivities: List<Double>

    var operator: SendableChooser<File> = SendableChooser()
    var driver: SendableChooser<File> = SendableChooser()

    var lastModified: Long = 0;

    var override_drivers: Boolean = false;
    var driver_file: File? = null;
    var operator_file: File? = null;

    var constants: C? = null;

    init {
        bindings = mutableListOf()
        usedBindings = HashSet()
        controllers = mutableListOf();
        controller_sensitivities = mutableListOf(0.5,0.5,0.5,0.5,0.5);

        for (i in 0..4) {
        }

        constants = makeConstantsClass(c.kotlin, getJsonConstants())!!
        
        if (constants == null) {
            DriverStation.reportWarning("fjdsakl;fjdsakl;", false);
        }

        DriverStation.reportWarning("final constants class $constants", false);

        rebuildProfiles()

        if (driver_lock != null && operator_lock != null) {
            resetCommands()
        }

        SmartDashboard.putData("unlock drivers", InstantCommand({
            this.unlockDrivers()
        }))
    }

    fun getJsonConstants(): JsonElement {
        val file = File(Filesystem.getDeployDirectory(), "bindings.json").readText();

        val withUnknown = Json {
            ignoreUnknownKeys = true
        };

        val s: SaveData = withUnknown.decodeFromString(file);

        return s.constants
    }

    fun constants(): C? {
        return constants
    }

    fun rebuildProfiles() {
        val children = File(Filesystem.getDeployDirectory(), "bindings").listFiles();

        operator = SendableChooser();
        driver = SendableChooser();

        for (child in children!!) {
            val name = child.nameWithoutExtension;
            operator.addOption(name, child);
            driver.addOption(name, child)
        }

        SmartDashboard.putData("operator choice", operator)
        SmartDashboard.putData("driver choice", driver)
    }

    fun unlockDrivers() {
        SmartDashboard.putData(driver);
        SmartDashboard.putData(operator);

        override_drivers = true;
    }

    fun makePov(): MutableList<MutableList<Command?>> {
        return makeButtons(9)
    }

    fun makeButtons(num: Int): MutableList<MutableList<Command?>> {
        val c:MutableList<MutableList<Command?>> = mutableListOf();

        for (i in 0..num) {
            c.add(mutableListOf(null,null,null,null))
        }

        return c
    }

    fun resetCommands() {
        val timer = Timer();

        timer.start()

        val op = if (operator_lock == null || override_drivers) {
            DriverStation.reportWarning("getting from dashboard", false)
            operator.selected
        } else {
            DriverStation.reportWarning("getting from file", false)

            File(File(Filesystem.getDeployDirectory(), "bindings"),
                "${operator_lock}.json")
        }

        val driver = if (driver_lock == null || override_drivers) {
            driver.selected
        } else {
            File(File(Filesystem.getDeployDirectory(), "bindings"),
                "${driver_lock}.json")
        };

        if (op == null || driver == null) {
            DriverStation.reportError("could not get driver or operator json (make sure they are both selected and the profiles exist)", false);
            return;
        }

        val withUnknown = Json {
            ignoreUnknownKeys = true
        };

        val savedata = File(Filesystem.getDeployDirectory(), "bindings.json");

        val time: Long = max(max(op.lastModified(), driver.lastModified()), savedata.lastModified());


        if (time <= lastModified && driver_file == driver && op == operator_file) {
            return
        }

        lastModified = time;
        driver_file = driver!!;
        operator_file = op!!;

        val d1:Profile = withUnknown.decodeFromString(op.readText());
        val d2:Profile = withUnknown.decodeFromString(driver.readText());

        if (constants != null) {
            DriverStation.reportError("CONSTANTS IS NULL", false);

            constants = c.cast(
                update_constants(
                    constants as Object,
                    getJsonConstants(),
                    d1.constants,
                    d2.constants));
        }

        bindings = mutableListOf()

        for ((a, b) in d1.controller_buttons.zip(d2.controller_buttons)) {
            bindings.add(Buttons(makePov(), makeButtons(max(a.first, b.first)), makeButtons(max(a.second, b.second))))
        }

        // arbitrary choice between the two of them because they shouldn't be double bound in the first place
        controller_sensitivities = d1.controller_sensitivities.zipWithNext { a, b ->  max(a,b)};

        for ((command, bindings) in d1.command_to_bindings) {
            add_bindings(command, bindings);
        }

        for ((command, bindings) in d2.command_to_bindings) {
            add_bindings(command, bindings);
        }

        rebuildProfiles()


        val time_took = timer.get()

        DriverStation.reportWarning("binding time ${time_took}", false)
    }

    fun add_bindings(command: String, bindings: List<Binding>) {
        for (binding in bindings) {
            if (!usedBindings.contains(binding)) {
                if (binding.controller >= controllers.size || binding.controller < 0) {
                    DriverStation.reportError("invalid controller found in binding", true);
                    continue;
                }
                val controller = controllers[binding.controller]

                if (controller == null) {
                    DriverStation.reportError("invalid controller found in binding", true);

                    continue;
                }

                mapBinding(controller, binding.controller, binding.button, binding.during)

                usedBindings.add(binding)
            }

            when (binding.button.location) {
                ButtonLocation.Button -> {
                    val a = this.bindings.get(binding.controller).regular[binding.button.button]
                    a[binding.during.ordinal] = if (a[binding.during.ordinal] == null) {
                        NamedCommands.getCommand(command)
                    } else {
                        a[binding.during.ordinal]!!.alongWith(NamedCommands.getCommand(command))
                    }
                }
                ButtonLocation.Pov -> {
                    val a = this.bindings.get(binding.controller).pov[povtoindex(binding.button.button)]
                    a[binding.during.ordinal] = if (a[binding.during.ordinal] == null) {
                        NamedCommands.getCommand(command)
                    } else {
                        a[binding.during.ordinal]!!.alongWith(NamedCommands.getCommand(command))
                    }
                }
                ButtonLocation.Analog -> {
                    val a = this.bindings.get(binding.controller).analog[binding.button.button]
                    a[binding.during.ordinal] = if (a[binding.during.ordinal] == null) {
                        NamedCommands.getCommand(command)
                    } else {
                        a[binding.during.ordinal]!!.alongWith(NamedCommands.getCommand(command))
                    }
                }
            }
        }
    }

    fun povtoindex(pov: Int): Int {
        return if (pov == -1) {
            8
        } else {
            pov/45
        }
    }

    fun mapBinding(c: CommandGenericHID, controller: Int, button: Button, run: RunWhen) {
        val (t, b) = when (button.location) {
            ButtonLocation.Button -> {
                Pair(c.button(button.button), button.button)
            }
            ButtonLocation.Pov -> {
                Pair(c.pov(button.button), povtoindex(button.button))
            }
            ButtonLocation.Analog -> {
                Pair(c.axisGreaterThan(button.button, controller_sensitivities[controller]), button.button)
            }
        };

        val select_command = MyProxyCommand({
            when (button.location) {
                ButtonLocation.Button -> bindings[controller].regular[b][run.ordinal]
                ButtonLocation.Pov -> bindings[controller].pov[b][run.ordinal]
                ButtonLocation.Analog -> bindings[controller].analog[b][run.ordinal]
            } ?: Commands.none()
        })

        when (run) {
            RunWhen.OnTrue -> {
                t.onTrue(select_command)
            }
            RunWhen.OnFalse -> {
                t.onFalse(select_command)
            }
            RunWhen.WhileTrue -> {
                t.whileTrue(select_command)
            }
            RunWhen.WhileFalse -> {
                t.whileFalse(select_command)
            }
        }

    }
}

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


// copied from wpilib because they are deprecating a constructor i need
class MyProxyCommand : Command {
    private val m_supplier: Supplier<Command>
    private var m_command: Command? = null

    constructor(supplier: Supplier<Command>) {
        m_supplier = ErrorMessages.requireNonNullParam(supplier, "supplier", "ProxyCommand")
    }

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

public class Constant<Value : Any>(private var value: Value /*, private val c: KClass<Value>*/) {
    val listeners = CopyOnWriteArrayList<(v: Value) -> Unit?>();

    public fun addListener(ev: (v: Value) -> Unit?) {
        listeners.add(ev);
    }

    public fun updateValueObject(v: Object) {
        this.updateValue(value::class.cast(v))
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
                tryGetPrimitive(a)
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

fun tryGetPrimitive(constants: JsonElement): JsonPrimitive? {
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

fun update_constants(
    constants: Object,
    key: MutableList<String>,
    global: JsonElement?,
    driver1: JsonElement?,
    driver2: JsonElement?): Object? {
    val res = when (constants) {
        is Int? -> {
            getOrDriver(global, driver1, driver2)?.int
        }
        is Double? -> {
            getOrDriver(global, driver1, driver2)?.double
        }
        is String -> {
            getOrDriver(global, driver1, driver2)?.content
        }
        is Constant<*> -> {
//            val c = constants.getValue()!!.javaClass;

            key.add("constant");

            val v = update_constants(constants.getValue() as Object, key, global, driver1, driver2);

            key.removeLast()

            if (v == null) {
                DriverStation.reportError("failed to update constant", false);
                null
            } else {
                constants.updateValueObject(v);

                DriverStation.reportWarning("constants value $constants", false);

                constants as Object
            }
        }
        else -> {
            val global = myGetJsonObject(myGetJsonObject(global)?.get("map"));
            val driver1 = myGetJsonObject(myGetJsonObject(driver1)?.get("map"));
            val driver2 = myGetJsonObject(myGetJsonObject(driver1)?.get("map"));

            for (field in constants::class.java.fields) {
                // DriverStation.reportWarning("accessing $field from $constants", false);

                val v = field.get(constants);

                val name = field.name;

                key.add(name);

                val o = update_constants(v as Object, key, global?.get(name), driver1?.get(name), driver2?.get(name))

                key.removeLast()

                if (o == null) {
                    return null
                } else {
                    field.set(constants, o)
                }
            }

            constants
        }
    } as Object?;

    if (res == null) {
        DriverStation.reportError("failed to make: $key", false);
    }

    return res;
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

fun getDefault(
    global: JsonElement?
): JsonPrimitive? {
    return when (global) {
        is JsonObject -> {
            global.get("default")?.jsonPrimitive
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
            getAsPrimitive(driver1) ?: getAsPrimitive(driver2) ?: getDefault(global)
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
class Bindings<C>(private val driver_lock: String?, private val operator_lock: String?, private val c: C) {
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

    public var constants: C;

    init {
        bindings = mutableListOf()
        usedBindings = HashSet()
        controllers = mutableListOf();
        controller_sensitivities = mutableListOf(0.5,0.5,0.5,0.5,0.5);

        for (i in 0..4) {
            controllers.add(CommandGenericHID(i))
        }

        val a = update_constants(c as Object, mutableListOf(), getJsonConstants(), null, null);

        if (a == null) {
            error("failed to update constants from default");
        } else {
            constants = c::class.cast(a);
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

//    fun constants(): C {
//        return constants
//    }

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

        DriverStation.reportWarning("driver selected ${driver}", false);
        DriverStation.reportWarning("operator selected ${op}", false);

        lastModified = time;
        driver_file = driver;
        operator_file = op;

        val d1:Profile = withUnknown.decodeFromString(op.readText());
        val d2:Profile = withUnknown.decodeFromString(driver.readText());

        val a = update_constants(
            constants as Object,
            mutableListOf(),
            getJsonConstants(),
            d1.constants,
            d2.constants);

        if (a != null) {
            constants = c!!::class.cast(a)
        } else {
            DriverStation.reportError("FAILED TO UPDATE CONSTANTS", false)
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
                    DriverStation.reportError("invalid controller found in binding ${binding.controller}", true);
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
            DriverStation.reportWarning("getting command to run", false);
            
            when (button.location) {
                ButtonLocation.Button -> bindings[controller].regular[b][run.ordinal]
                ButtonLocation.Pov -> bindings[controller].pov[b][run.ordinal]
                ButtonLocation.Analog -> bindings[controller].analog[b][run.ordinal]
            } ?: InstantCommand({
                DriverStation.reportWarning("failed to get command for location", false)
            })
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

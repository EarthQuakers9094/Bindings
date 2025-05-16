package bindings

import com.pathplanner.lib.auto.NamedCommands
import com.sun.org.apache.xpath.internal.operations.Bool
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
import org.json.simple.JSONObject
import java.io.File
import java.time.InstantSource
import java.util.function.Supplier
import kotlin.math.max
import java.nio.file.attribute.FileTime

// copied from wpilib because they are deprecating a constructor i need
class MyProxyCommand : Command {
    private val m_supplier: Supplier<Command>
    private var m_command: Command? = null

    constructor(supplier: Supplier<Command>) {
        m_supplier = ErrorMessages.requireNonNullParam(supplier, "supplier", "ProxyCommand")
    }

    constructor(command: Command) {
        val nullCheckedCommand = ErrorMessages.requireNonNullParam(command, "command", "ProxyCommand")
        m_supplier = Supplier { nullCheckedCommand }
        name = "Proxy(" + nullCheckedCommand.name + ")"
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

fun myGetJsonObject(e: JsonElement): JsonObject? {
    return when (e) {
        is JsonObject -> e.jsonObject
        else -> null
    }
}

@Serializable
data class SaveData(
    val command_to_bindings: HashMap<String, List<Binding>>,
    val controllers: List<JsonElement>, // handling raw
    val controller_names: List<String>,
) {
    val controller_sensitivities = controllers.map { 
        val t = (myGetJsonObject(it)?.get("XBox")?.jsonObject?.get("sensitivity")?.jsonPrimitive?.double);

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
class Bindings(private val driver_lock: String?,private val operator_lock: String?) {
    val usedBindings: HashSet<Binding>;
    var bindings: MutableList<Buttons>;
    var controllers: MutableList<CommandGenericHID?>
    var controller_sensitivities: List<Double>

    var operator: SendableChooser<File> = SendableChooser()
    var driver: SendableChooser<File> = SendableChooser()

    var lastModified: Long;

    var override_drivers: Boolean = false;
    var driver_file: File? = null;
    var operator_file: File? = null;

    init {
        bindings = mutableListOf()
        usedBindings = HashSet()
        controllers = mutableListOf();
        controller_sensitivities = mutableListOf(0.5,0.5,0.5,0.5,0.5);

        for (i in 0..4) {
            controllers.add(CommandGenericHID(i))
        }

        rebuildProfiles()

        lastModified = 0;

        if (driver_lock != null && operator_lock != null) {
            resetCommands()
        }

        SmartDashboard.putData("unlock drivers", InstantCommand({
            this.unlockDrivers()
        }))
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

        val time: Long = max(op.lastModified(), driver.lastModified());

        if (time <= lastModified && driver_file == driver && op == operator_file) {
            return
        }

        lastModified = time;
        driver_file = driver!!;
        operator_file = op!!;

        val d1:SaveData = withUnknown.decodeFromString(op.readText());
        val d2:SaveData = withUnknown.decodeFromString(driver.readText());

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

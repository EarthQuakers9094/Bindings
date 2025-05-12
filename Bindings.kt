import com.pathplanner.lib.auto.NamedCommands
import edu.wpi.first.util.ErrorMessages
import edu.wpi.first.util.sendable.SendableBuilder
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.Filesystem
import edu.wpi.first.wpilibj.Timer
import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.Commands
import edu.wpi.first.wpilibj2.command.button.CommandGenericHID
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.json.simple.JSONObject
import java.io.File
import java.util.function.Supplier

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

fun getJsonObject(e: JsonElement): JsonObject? {
    return when (e) {
        is JsonObject -> e.jsonObject
        else -> null
    }
}

fun getNumButtons(e: JsonElement): Pair<Int, Int> {
    when (e) {
        is JsonObject -> {
            return if (e.keys.contains("XBox")) {
                Pair(10, 6)
            } else {
                Pair(e["Generic"]!!.jsonObject["buttons"]!!.jsonPrimitive.int, 0)
            }
        }
        else -> {
            return Pair(0,0)
        }
    }
}

fun getSensitivities(it: JsonElement): Double {
    val t = (getJsonObject(it)?.get("XBox")?.jsonObject?.get("sensitivity")?.jsonPrimitive?.double);

    return if (t == null) {
        0.5
    } else {
        t
    }
}

@Serializable
data class SaveData(
    val url: String?, 
    val commands: HashSet<String>, 
    val command_to_bindings: HashMap<String, List<Binding>>,
    val controllers: List<JsonElement>, // handling raw
    val controller_names: List<String>,
) {
    val controller_sensitivities = controllers.map { getSensitivities(it) }
    val controller_buttons = controllers.map { getNumButtons(it) }
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

class Bindings() {
    val usedBindings: HashSet<Binding>;
    var bindings: MutableList<Buttons>;
    var controllers: MutableList<CommandGenericHID?>;
    var controller_sensitivities: List<Double>

    init {
        bindings = mutableListOf()
        usedBindings = HashSet()
        controllers = mutableListOf();
        controller_sensitivities = mutableListOf(0.5,0.5,0.5,0.5,0.5);

        for (i in 0..4) {
            controllers.add(CommandGenericHID(i))
        }

        resetCommands()
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

        val file = File(Filesystem.getDeployDirectory(), "bindings.json").readText();

        val data:SaveData = Json.decodeFromJsonElement(Json.parseToJsonElement(file));

        bindings = mutableListOf()

        for ((reg, analog) in data.controller_buttons) {
            bindings.add(Buttons(makePov(), makeButtons(reg), makeButtons(analog)))
        }

        controller_sensitivities = data.controller_sensitivities;

        for ((command, bindings) in data.command_to_bindings) {
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

        val time = timer.get()

        DriverStation.reportWarning("binding time ${time}", false)
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

//            (if (pov) {
//                bindings[controller].pov[b][run.ordinal]
//            } else {
//                bindings[controller].regular[b][run.ordinal]
//            }) ?: Commands.none()
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
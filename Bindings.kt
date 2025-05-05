import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.button.CommandGenericHID
import edu.wpi.first.wpilibj2.command.ProxyCommand
import edu.wpi.first.wpilibj2.command.SelectCommand
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard
import kotlin.collections.listOf
import kotlin.collections.mutableListOf
import kotlin.collections.HashMap
import kotlinx.serialization.*;
import kotlinx.serialization.json.*;
import com.pathplanner.lib.auto.NamedCommands

@Serializable
data class SaveData(
    val url: String?, 
    val commands: HashSet<String>, 
    val command_to_bindings: HashMap<String, List<Binding>>)

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
    Pov
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
    val pov: MutableList<MutableList<Int>>,
    val regular: MutableList<MutableList<Int>>
)

class Bindings(val controllers_buttons: List<Int>) {
    var current_command = 0;
    val usedBindings: HashSet<Binding>;
    var bindings: MutableList<Buttons>;
    var controllers: MutableList<CommandGenericHID?>;
    var command_map: HashMap<Int, Command>;
    var command_to_id: HashMap<String, Int>;

    init {
        bindings = mutableListOf()
        usedBindings = HashSet()
        controllers = mutableListOf();
        command_map = HashMap();
        command_to_id = HashMap();

        
        var i = 0;
        
        for (buttons in controllers_buttons) {
            if (buttons != 0) {
                controllers.add(CommandGenericHID(i))
            } else {
                controllers.add(null)
            }
        }

        resetCommands()
    }

    fun makePov(): MutableList<MutableList<Int>> {
        return makeButtons(9)
    }

    fun makeButtons(num: Int): MutableList<MutableList<Int>> {
        val c:MutableList<MutableList<Int>> = mutableListOf();

        for (i in 0..num) {
            c.add(mutableListOf(-1,-1,-1,-1))
        }

        return c
    }

    fun resetCommands() {

        bindings = mutableListOf()

        for (i in controllers_buttons) {
            bindings.add(Buttons(makePov(), makeButtons(i)))
        }

        val data:SaveData = Json.decodeFromString("src/main/deploy/bindings.json");

        registerCommands(data.commands);

        for ((command, bindings) in data.command_to_bindings) {
            for (binding in bindings) {
                if (!usedBindings.contains(binding)) {
                    if (binding.controller >= controllers.size || binding.controller < 0) {
                        DriverStation.reportError("invalid controller found in binding", true);
                        continue;
                    }
                    val controller = controllers.get(binding.controller)

                    if (controller == null) {
                        DriverStation.reportError("invalid controller found in binding", true);
                        
                        continue;
                    }

                    mapBinding(controller, binding.controller, binding.button, binding.during);                    
                }

                if (!command_to_id.containsKey(command)) {
                    DriverStation.reportError("invalid command", true)
                    continue;
                }

                val id = command_to_id.get(command)!!

                if (binding.button.location == ButtonLocation.Pov) {
                    this.bindings.get(binding.controller).pov[binding.button.button][binding.during.ordinal] = id
                } else {
                    this.bindings.get(binding.controller).regular[binding.button.button].set(binding.during.ordinal, id)
                }
            }
        }
    }

    fun registerCommands(commands: HashSet<String>) {
        for (c in commands) {
            registerCommand(c)
        }
    }

    fun registerCommand(command: String) {
        if (command_to_id.contains(command)) {
            return;
        }

        command_map.set(current_command, NamedCommands.getCommand(command))
        command_to_id.set(command, current_command)

        current_command += 1;

    }

    fun povtoindex(pov: Int): Int {
        return if (pov == -1) {
            8
        } else {
            pov/45
        }
    }

    fun mapBinding(c: CommandGenericHID, controller: Int, button: Button, run: RunWhen) {
        val (t, b, pov) = when (button.location) {
            ButtonLocation.Button -> {
                Triple(c.button(button.button), button.button, false)
            }
            ButtonLocation.Pov -> {
                Triple(c.pov(button.button), povtoindex(button.button), true)
            }
        };

        val select_command = SelectCommand(command_map, {
            val con = bindings[controller];

            if (pov) {
                con.pov[b][run.ordinal]
            } else {
                con.regular[b][run.ordinal]
            }
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
import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.button.CommandGenericHID
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard
import kotlin.collections.listOf
import kotlin.collections.mutableListOf
import kotlinx.serialization.*;
import kotlinx.serialization.json.*;

@Serializable
data class SaveData(
    val url: String?, 
    val commands: HashSet<String>, 
    val command_to_bindings: HashMap<String, List<Binding>>)

@Serializable
enum RunWhen {
    OnTrue,
    OnFalse,
    WhileTrue,
    WhileFalse,
}

@Serializable
enum ButtonLocation {
    Button,
    Pov
}

@Serializable
data class Button(val button: Int, val location: ButtonLocation)

@Serializable
data class Binding(
    val controller: Int,
    val button: Button,
    val when: RunWhen
)

data class Buttons(
    val pov: List<Command?>,
    val regular: List<Command?>
)

class Bindings(val controllers_buttons: List<Int>) {
    val usedBindings: HashSet<Binding>;
    var bindings: MutableList<Buttons>,
    var controllers: MutableList<CommandGenericHID?>,

    init {
        bindings = mutableListOf()
        usedBindings = HashSet()
        controllers = mutableListOf();
        
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

    fun makePov(): List<Command?> {
        return makeButtons(9)
    }

    fun makeButtons(num: Int): List<Command?> {
        val c:MutableList<Command?> = mutableListOf();

        for (i in 0..num) {
            c.add(null)
        }

        return c
    }

    fun resetCommands() {
        bindings = mutableListOf()

        for (i in controllers_buttons) {
            bindings.add(Buttons(makePov(), makeButtons(i)))
        }

        val data:SaveData = Json.decodeFromString("src/main/deploy/bindings.json");

        for ((command, bindings) in data.command_to_bindings) {
            for (binding in bindings) {
                if (!usedBindings.contains(binding)) {
                    if (binding.controller >= controllers.size) {
                        DriverStation.reportError("invalid controller found in binding", false);
                        continue;
                    }
                    val button = binding.button;
                    val controller = controllers.get(binding.controller)

                    if (controller == null) {
                        continue;
                    }

                    mapBinding(controller, binding.button, binding.`when`);

                }
            }
        }
    }

    fun mapBinding(c: CommandGenericHID, button: Button, run: RunWhen) {
        val triggers = when (button.location) {
            ButtonLocation.Button => {
                c.button(p0)
                
            },
            ButtonLocation.Pov => {

            }
        }
    }
}
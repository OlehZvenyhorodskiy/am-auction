package de.cubeisland.AuctionHouse;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * This class is the base for all sub commands
 *
 * @author Phillip Schichtel
 */
public class BaseCommand implements CommandExecutor, TabCompleter
{
    public final String permissinBase;
    private final Plugin plugin;
    private final PluginManager pm;
    private final HashMap<String, AbstractCommand> allSubCommands;
    private final HashMap<String, AbstractCommand> subCommands;
    private final Permission parentPermission;
    private String defaultCommand;
    private String label;

    public BaseCommand(Plugin plugin)
    {
        this.plugin = plugin;
        this.pm = plugin.getServer().getPluginManager();
        this.permissinBase = this.plugin.getDescription().getName().toLowerCase() + ".commands.";
        this.defaultCommand = null;
        this.allSubCommands = new HashMap<String, AbstractCommand>();
        this.subCommands = new HashMap<String, AbstractCommand>();
        this.parentPermission = new Permission(permissinBase + "*", PermissionDefault.OP);
        try
        {
            this.pm.addPermission(this.parentPermission);
        }
        catch (IllegalArgumentException e)
        {
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        this.label = label;
        if (args.length > 0)
        {
            AbstractCommand cmd = this.allSubCommands.get(args[0].toLowerCase());
            if (cmd != null)
            {
                return executeSub(sender, cmd, args);
            }
        }

        if (this.defaultCommand != null)
        {
            AbstractCommand defaultSub = this.allSubCommands.get(this.defaultCommand);
            if (defaultSub != null)
            {
                if (!(sender instanceof org.bukkit.entity.Player) && "menu".equalsIgnoreCase(defaultSub.getLabel()) && this.allSubCommands.containsKey("help"))
                {
                    return executeSub(sender, this.allSubCommands.get("help"), new String[]{"help"});
                }
                return executeSub(sender, defaultSub, new String[]{this.defaultCommand});
            }
        }

        sender.sendMessage(t("help_list"));
        for (String commandLabel : this.allSubCommands.keySet())
        {
            sender.sendMessage(" - " + commandLabel);
        }

        return true;
    }

    private boolean executeSub(CommandSender sender, AbstractCommand command, String[] args)
    {
        /*
         * 
        if (!sender.hasPermission(command.getPermission()))
        {
            sender.sendMessage(ChatColor.RED + "Permission denied!");
            return true;
        }
        * 
        */
        return command.execute(sender, new CommandArgs(args));
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
    {
        List<String> out = new ArrayList<String>();
        if (args == null || args.length <= 1)
        {
            String prefix = args == null || args.length == 0 || args[0] == null ? "" : args[0].toLowerCase();
            for (String commandLabel : this.allSubCommands.keySet())
            {
                if (commandLabel == null)
                {
                    continue;
                }
                String lower = commandLabel.toLowerCase();
                if (lower.startsWith(prefix))
                {
                    out.add(commandLabel);
                }
            }
            Collections.sort(out);
            return out;
        }

        String sub = args[0] == null ? "" : args[0].toLowerCase();
        String current = args[args.length - 1] == null ? "" : args[args.length - 1].toLowerCase();
        if (("clearall".equals(sub) || "clearauction".equals(sub) || "purge".equals(sub) || "resetauction".equals(sub)) && args.length == 2)
        {
            if ("confirm".startsWith(current))
            {
                out.add("confirm");
            }
            if ("yes".startsWith(current))
            {
                out.add("yes");
            }
            return out;
        }
        if (("sell".equals(sub) || "s".equals(sub) || "sellinventory".equals(sub) || "si".equals(sub)) && args.length == 2)
        {
            out.add("100");
            out.add("500");
            out.add("1000");
            return out;
        }
        if (("sell".equals(sub) || "s".equals(sub)) && args.length == 3)
        {
            out.add("1");
            out.add("8");
            out.add("16");
            out.add("32");
            out.add("64");
            return out;
        }
        return out;
    }

    /**
     * Registeres a sub command
     *
     * @param command the command to register
     * @return fluent interface
     */
    public BaseCommand registerSubCommand(AbstractCommand command)
    {
        for (String subLabel : command.getLabels())
        {
            this.allSubCommands.put(subLabel.toLowerCase(), command);
        }
        this.subCommands.put(command.getLabel().toLowerCase(), command);
        final Permission perm = command.getPermission();
        try
        {
            this.pm.addPermission(perm);
        }
        catch (IllegalArgumentException e)
        {
        }
        perm.addParent(this.parentPermission, true);
        perm.recalculatePermissibles();
        return this;
    }

    /**
     * Unregisteres a sub command
     *
     * @param name the name of the sub command to unregister
     * @return fluent interface
     */
    public BaseCommand unregisterSubCommand(String name)
    {
        this.allSubCommands.remove(name);
        if (name.equals(this.defaultCommand))
        {
            this.defaultCommand = null;
        }
        return this;
    }

    /**
     * Unregisteres all sub commands
     *
     * @return fluent interface
     */
    public BaseCommand unregisterAllSubCommands()
    {
        this.allSubCommands.clear();
        return this;
    }

    /**
     * Sets the default command
     *
     * @param name the name of a registered command
     * @return fluent interface
     */
    public BaseCommand setDefaultCommand(String name)
    {
        name = name.toLowerCase();
        if (this.allSubCommands.containsKey(name))
        {
            this.defaultCommand = name;
        }
        return this;
    }

    /**
     * Returns a collection of the registered sub commands with alias
     *
     * @return the commands
     */
    public Collection<AbstractCommand> getAllRegisteredCommands()
    {
        return this.allSubCommands.values();
    }
    
    /**
     * Returns a collection of the registered sub commands without alias
     *
     * @return the commands
     */
    public Collection<AbstractCommand> getRegisteredCommands()
    {
        return this.subCommands.values();
    }

    /**
     * Returns the label of this command
     * This is only used by sub commands
     *
     * @return
     */
    public String getLabel()
    {
        return this.label;
    }

    /**
     * Returns the corresponding plugin
     *
     * @return this plugin
     */
    public Plugin getPlugin()
    {
        return this.plugin;
    }
}

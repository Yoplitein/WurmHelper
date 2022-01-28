package net.ildar.wurm.bot;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;

import com.wurmonline.client.console.WurmConsole;
import com.wurmonline.client.game.World;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.TilePicker;
import com.wurmonline.client.renderer.cave.CaveWallPicker;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.gui.CreationFrame;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.shared.constants.PlayerAction;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import net.ildar.wurm.BotController;
import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;
import net.ildar.wurm.bot.MinerBot.Direction;

@BotInfo(description = "Remotely control other clients", abbreviation = "rmi")
public class RMIBot extends Bot implements BotServer, BotClient, Executor
{
    final HeadsUpDisplay hud = WurmHelper.hud;
    final World world = hud.getWorld();
    
    static final String registryPrefix = RMIBot.class.getSimpleName();
    String registryHost = "127.0.43.7";
    int registryPort = 0x2B07;
    
    ArrayBlockingQueue<Runnable> oneshotTasks = new ArrayBlockingQueue<>(32, false);
    ArrayList<ScheduledTask> scheduledTasks = new ArrayList<>();
    
    // server fields
    ClientSet clients;
    Registry serverRegistry;
    
    boolean syncPosition;
    boolean syncHeading;
    long syncDelay = 50;
    
    // client fields
    Registry clientRegistry;
    long shovelID = -10;
    long pickaxeID = -10;
    
    boolean isServer()
    {
        return serverRegistry != null;
    }
    
    boolean isClient()
    {
        return clientRegistry != null;
    }
    
    static boolean printExceptions(ThrowingRunnable fn, String fmt, Object... args)
    {
        try
        {
            fn.run();
            return true;
        }
        catch(Exception err)
        {
            Utils.consolePrint(
                String.format("%s: %s", RMIBot.class.getSimpleName(), fmt), // preserve format arg numbering
                err.getClass().getName(),
                err.getMessage(),
                args
            );
            err.printStackTrace();
            return false;
        }
    }
    
    public RMIBot()
    {
        registerInputHandler(
            Inputs.s,
            input -> printExceptions(
                () -> toggleServerMode(),
                "Got %s when toggling server mode: %s"
            )
        );
        registerInputHandler(
            Inputs.c,
            input -> printExceptions(
                () -> toggleClientMode(),
                "Got %s when toggling client mode: %s"
            )
        );
        registerInputHandler(
            Inputs.sl,
            input -> printExceptions(
                () -> serverListClients(),
                "Got %s when listing clients: %s"
            )
        );
        registerInputHandler(
            Inputs.slr,
            input -> printExceptions(
                () -> serverRefreshClients(),
                "Got %s when refreshing clients: %s"
            )
        );
        registerInputHandler(
            Inputs.sr,
            input -> printExceptions(
                () -> serverRun(input),
                "Got %s when running server command: %s"
            )
        );
        registerInputHandler(Inputs.regaddr, this::setRegistryAddress);
    }
    
    @Override
    public void setPaused()
    {
        Utils.consolePrint("%s cannot be paused", getClass().getSimpleName());
    }
    
    @Override
    public synchronized void setResumed()
    {
        Utils.consolePrint("%s cannot be paused", getClass().getSimpleName());
    }
    
    @Override
    public synchronized void execute(Runnable task)
    {
        oneshotTasks.add(task);
        notify();
    }
    
    synchronized void schedule(Runnable task, long msecs)
    {
        final long now = System.currentTimeMillis();
        scheduledTasks.add(new ScheduledTask(task, now + msecs));
        notify();
    }
    
    @Override
    void work() throws Exception
    {
        try
        {
            ArrayList<ScheduledTask> expiredTasks = new ArrayList<>();
            
            while(isActive())
            {
                try
                {
                    synchronized(this)
                    {
                        while(scheduledTasks.isEmpty() && oneshotTasks.isEmpty())
                            wait();
                    
                        long now = System.currentTimeMillis();
                        expiredTasks.clear();
                        for(ScheduledTask task: scheduledTasks)
                            if(task.triggerTime <= now)
                                // copy tasks so we can remove them while iterating
                                expiredTasks.add(task);
                        for(ScheduledTask task: expiredTasks)
                        {
                            scheduledTasks.remove(task);
                            task.task.run();
                        }
                        
                        // current oneshots may queue subsequent oneshots, but those should be run next iteration
                        int oneshotsToRun = oneshotTasks.size();
                        while(oneshotsToRun-- > 0)
                            oneshotTasks.remove().run();
                        
                        final long nextTaskTime = scheduledTasks
                            .stream()
                            .map(task -> task.triggerTime)
                            .min(Comparator.comparingLong(x -> x))
                            .orElse(Long.MAX_VALUE)
                        ;
                        now = System.currentTimeMillis();
                        while(
                            oneshotTasks.size() == 0 && // don't sleep if there are pending oneshots
                            nextTaskTime - now > 0
                        )
                        {
                            // there should probably be a synchronized block around here
                            wait(Math.max(0, nextTaskTime - now));
                            now = System.currentTimeMillis();
                        }
                    }
                }
                catch(InterruptedException err)
                {
                    break;
                }
                catch(Exception err)
                {
                    Utils.consolePrint(
                        "%s: Got %s when running tasks: %s",
                        getClass().getSimpleName(),
                        err.getClass().getName(),
                        err.getMessage()
                    );
                    err.printStackTrace();
                }
            }
        }
        finally
        {
            printExceptions(() -> {
                if(isClient()) toggleClientMode();
                if(isServer()) toggleServerMode();
            }, "Got %1$s when deactivating: %2$s");
            Utils.consolePrint("%s shut down", getClass().getSimpleName());
        }
    }
    
    void toggleServerMode() throws Exception
    {
        if(isClient())
        {
            Utils.consolePrint("Can't enable server mode while client mode is enabled");
            return;
        }
        
        if(isServer())
        {
            for(String name: serverRegistry.list())
                printExceptions(
                    () -> serverRegistry.unbind(name),
                    "Got %1$s when unbinding client %3$s: %2$s",
                    name
                );
            
            clients = null;
            syncPosition = false; // kill syncPositionTask
            syncHeading = false;
            
            printExceptions(
                () -> serverRegistry.unbind("BotServer"),
                "Got %s when unbinding server: %s"
            );
            printExceptions(
                () -> UnicastRemoteObject.unexportObject(this, true),
                "Got %s when unexporting server: %s"
            );
            
            printExceptions(
                () -> UnicastRemoteObject.unexportObject(serverRegistry, true),
                "Got %s when unexporting registry: %s"
            );
            serverRegistry = null;
            
            Utils.consolePrint("Server mode disabled, RMI registry shut down");
            return;
        }
        
        serverRegistry = LocateRegistry.createRegistry(
            registryPort,
            RMISocketFactory.getDefaultSocketFactory(),
            port -> new ServerSocket(port, 16, InetAddress.getByName(registryHost))
        );
        
        Remote stub = UnicastRemoteObject.exportObject(this, 0);
        serverRegistry.bind("BotServer", stub);
        
        clients = new ClientSet();
        
        Utils.consolePrint("Server mode active, RMI registry running at %s:%d", registryHost, registryPort);
    }
    
    void toggleClientMode() throws Exception
    {
        if(isServer())
        {
            Utils.consolePrint("Can't enable client mode while server mode is enabled");
            return;
        }
        
        final String regName = String.format("%s_%s", registryPrefix, getPlayerName());
        
        if(isClient())
        {
            printExceptions(
                () -> clientRegistry.unbind(regName),
                "Got %s when unbinding client: %s"
            );
            printExceptions(
                () -> UnicastRemoteObject.unexportObject(this, true),
                "Got %s when unexporting client: %s"
            );
            printExceptions(
                () -> ((BotServer)clientRegistry.lookup("BotServer")).onClientLeave(getPlayerName()),
                "Got %s when notifying server about client disconnect: %s"
            );
            clientRegistry = null;
            
            Utils.consolePrint("Client mode disabled");
            return;
        }
        
        clientRegistry = LocateRegistry.getRegistry(registryHost, registryPort);
        Remote stub = UnicastRemoteObject.exportObject(this, 0);
        clientRegistry.bind(regName, stub);
        Utils.consolePrint("Client mode enabled");
        ((BotServer)clientRegistry.lookup("BotServer")).onClientJoin(getPlayerName());
    }
    
    void serverRefreshClients() throws Exception
    {
        if(!isServer())
        {
            Utils.consolePrint("This command can only be used in server mode");
            return;
        }
        assert clients != null;
        
        clients.refreshRemotes(serverRegistry);
    }
    
    void serverListClients() throws Exception
    {
        if(!isServer())
        {
            Utils.consolePrint("This command can only be used in server mode");
            return;
        }
        assert clients != null;
        
        String allNames = clients.getPlayerName();
        if(allNames.length() > 0)
            Utils.consolePrint("Clients: %s", allNames);
        else
            Utils.consolePrint("No clients");
    }
    
    void serverRun(String[] args) throws Exception
    {
        if(!isServer())
        {
            Utils.consolePrint("This command can only be used in server mode");
            return;
        }
        
        if(args == null || args.length == 0)
        {
            Utils.consolePrint("Must specify a subcommand");
            return;
        }
        
        final int mouseX = world.getClient().getXMouse();
        final int mouseY = world.getClient().getYMouse();
        final PickableUnit hoveredUnit = world.getCurrentHoveredObject();
        
        switch(args[0].toLowerCase())
        {
            case "help":
            {
                final String botKeyword = BotController.getInstance().getBotRegistration(this.getClass()).getAbbreviation();
                final String cmdKeyword = Inputs.sr.getName();
                
                Utils.consolePrint(
                    "bot %s %s exec [@characterName] <cmd1 [\\; cmd2...]> -- clients (or only named client,) but not master, execute given commands",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s action <action id> <target id> -- everyone performs action on target",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s attack -- everyone targets hovered creature",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s syncpos -- clients have position continuously synced with master",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s synclook -- clients have orientation continuously synced with master",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s synctime -- set interval (msecs) for position/heading synchronization",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s embark -- clients (+ master as driver) embark onto hovered vehicle",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s disembark -- everyone disembarks their current vehicle",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s dig -- everyone shovels up resources",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s level -- everyone levels out hovered tile",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s mine <f/u/d/v> -- everyone mines hovered wall in specified direction " +
                    "(as in MinerBot, or floor/ceiling with `v`)",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s eat -- everyone eats the hovered food item",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s drink -- everyone drinks the hovered water tile/item",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s addtocrafting -- everyone adds the hovered item to crafting window",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s clearcrafting -- everyone clears both slots in crafting window",
                    botKeyword, cmdKeyword
                );
                break;
            }
            
            case "exec":
            {
                String[] cmdBits = Arrays.copyOfRange(args, 1, args.length);
                String target = null;
                
                if(cmdBits.length > 0 && cmdBits[0].startsWith("@"))
                {
                    target = cmdBits[0].substring(1).toLowerCase();
                    cmdBits = Arrays.copyOfRange(cmdBits, 1, cmdBits.length);
                }
                
                if(cmdBits.length == 0 || target != null && target.length() == 0)
                {
                    Utils.consolePrint("Bad arguments");
                    break;
                }
                
                String[] commands = String.join(" ", cmdBits).split("(?<!#)#(?!#)");
                for(int index = 0; index < commands.length; index++)
                    commands[index] = commands[index].replace("##", "#");
                
                if(target == null)
                    clients.execCmds(commands);
                else
                {
                    boolean found = false;
                    for(BotClient remote: clients.remotes)
                        if(remote.getPlayerName().equalsIgnoreCase(target))
                        {
                            found = true;
                            remote.execCmds(commands);
                        }
                    
                    if(!found)
                        Utils.consolePrint("Couldn't find a player with name `%s`", target);
                }
                break;
            }
            
            case "action":
            {
                if(args.length < 2)
                {
                    
                    return;
                }
                
                short actionID;
                try
                {
                    actionID = Short.parseShort(args[1]);
                }
                catch(NumberFormatException err)
                {
                    Utils.consolePrint("`%s` is not a 16 bit integer", args[1]);
                    break;
                }
                
                long[] targets = hud.getCommandTargetsFrom(mouseX, mouseY);
                if(targets == null || targets.length == 0)
                {
                    if(hoveredUnit != null)
                        targets = new long[]{hoveredUnit.getId()};
                    else
                    {
                        Utils.consolePrint("Not hovering over any item/creature/tile");
                        break;
                    }
                }
                
                genericAction(actionID, targets[0]);
                clients.genericAction(actionID, targets[0]);
            }
            
            // case "select": // TODO: sync everyone's selection (select bar) with server, for commands
            
            // TODO: everyone cancels all actions
            // it might be better to hook into the stop binding directly (somehow) and relay them
            // case "stop":
            
            case "attack":
            {
                if(hoveredUnit == null || !(hoveredUnit instanceof CreatureCellRenderable))
                {
                    Utils.consolePrint("Not hovering over creature");
                    break;
                }
                
                attack(hoveredUnit.getId());
                clients.attack(hoveredUnit.getId());
                break;
            }
            
            // TODO: everyone targets a (random) creature anyone is targeting
            // perhaps if we can get opponents, pick that of the char with the least health
            // case "synctarget":
            
            case "syncpos":
            {
                syncPosition = !syncPosition;
                if(syncPosition && !syncHeading)
                    execute(this::syncPositionTask);
                
                Utils.consolePrint(
                    "Position synchronization %s",
                    syncPosition ? "on" : "off"
                );
                break;
            }
            
            case "synclook":
            {
                syncHeading = !syncHeading;
                if(syncHeading && !syncPosition)
                    execute(this::syncPositionTask);
                
                Utils.consolePrint(
                    "Heading synchronization %s",
                    syncHeading ? "on" : "off"
                );
                break;
            }
            
            case "synctime":
            {
                if(args.length < 2)
                {
                    Utils.consolePrint("Current sync delay is %dms", syncDelay);
                    break;
                }
                
                try
                {
                    long newDelay = Long.parseLong(args[1]);
                    if(newDelay <= 0)
                    {
                        Utils.consolePrint("Timeout must be > 0!");
                        break;
                    }
                    syncDelay = newDelay;
                    Utils.consolePrint("Sync delay is now %dms", syncDelay);
                }
                catch(NumberFormatException err)
                {
                    Utils.consolePrint("`%s` is not an integer", args[1]);
                    break;
                }
                break;
            }
            
            case "embark":
            {
                if(hoveredUnit == null || !(hoveredUnit instanceof CreatureCellRenderable))
                {
                    Utils.consolePrint("Not hovering over vehicle");
                    break;
                }
                
                long id = hoveredUnit.getId();
                hud.sendAction(PlayerAction.EMBARK_DRIVER, id);
                clients.embark(id);
                break;
            }
            
            case "disembark":
            {
                disembark();
                clients.disembark();
                break;
            }
            
            case "dig":
            {
                dig();
                clients.dig();
                break;
            }
            
            case "level":
            {
                if(hoveredUnit == null || !(hoveredUnit instanceof TilePicker))
                {
                    Utils.consolePrint("Not hovering over any tile");
                    return;
                }
                
                final long tileID = hoveredUnit.getId();
                level(tileID);
                clients.level(tileID);
                
                break;
            }
            
            case "mine":
            {
                final String strDir = args.length >= 2 ? args[1].toLowerCase() : "f";
                final Direction direction = Direction.getByAbbreviation(strDir);
                
                // vertical mining has its own "direction" to guard against wayward mining actions mucking up e.g. smooth floors
                final boolean targetingVertical = strDir.equalsIgnoreCase("v"); // what the user *wants* to target
                
                if(direction == Direction.UNKNOWN && !targetingVertical)
                {
                    Utils.consolePrint("Unkown mining direction `%s` (expecting f/u/d/v)", args[1]);
                    return;
                }
                
                if(hoveredUnit == null || !(hoveredUnit instanceof CaveWallPicker))
                {
                    Utils.consolePrint("Not hovering over any cave tile");
                    return;
                }
                
                // what the user is *actually* hovering over
                final boolean targetedVertical = ((CaveWallPicker)hoveredUnit).getWallId() < 2; // ids 0,1 are floor/ceiling
                
                // FIXME: even with this check there's been unwanted floor mining
                // possibly need to also check on clients, the wall ID may get reused for the floor?
                // reminder: a similar fix should also apply to MinerBot
                if(targetingVertical != targetedVertical)
                {
                    Utils.consolePrint(
                        "Direction mismatch: direction is %s but hovering over %s%s",
                        targetingVertical ? "floor/ceiling" : "walls",
                        targetedVertical ? "floor/ceiling" : "a wall",
                        targetedVertical ? " (did you mean to use `v` as the direction?)" : ""
                    );
                    return;
                }
                
                final long wallID = hoveredUnit.getId();
                mine(wallID, direction);
                clients.mine(wallID, direction);
                break;
            }
            
            case "eat":
            {
                long[] targets = hud.getCommandTargetsFrom(mouseX, mouseY);
                if(targets == null || targets.length == 0)
                {
                    Utils.consolePrint("Not hovering over any items");
                    break;
                }
                
                clients.genericAction((short)182, targets[0]); // no PlayerAction constant for eat/drink for some reason
                clients.genericAction((short)182, targets[0]);
                break;
            }
            
            case "drink":
            {
                long[] targets = hud.getCommandTargetsFrom(mouseX, mouseY);
                if(targets == null || targets.length == 0)
                {
                    if(hoveredUnit != null && (hoveredUnit instanceof TilePicker))
                        targets = new long[]{hoveredUnit.getId()};
                    else
                    {
                        Utils.consolePrint("Not hovering over any item/tile");
                        break;
                    }
                }
                
                genericAction((short)183, targets[0]);
                clients.genericAction((short)183, targets[0]);
                break;
            }
            
            // TODO: clients answer a question (BML window)
            // need to investigate whether this is even feasible
            // case "bml": // maybe "question"
            
            case "addtocrafting":
            {
                if(hoveredUnit == null || !(hoveredUnit instanceof GroundItemCellRenderable))
                {
                    Utils.consolePrint("Not hovering over any ground items");
                    return;
                }
                
                final long id = hoveredUnit.getId();
                genericAction(PlayerAction.ADD_TO_CRAFTING_WINDOW.getId(), id);
                clients.genericAction(PlayerAction.ADD_TO_CRAFTING_WINDOW.getId(), id);
                break;
            }
            
            case "clearcrafting":
            {
                clearCrafting();
                clients.clearCrafting();
                break;
            }
            
            default:
                Utils.consolePrint("Unknown subcommand `%s`", args[0]);
        }
    }
    
    void setRegistryAddress(String[] args)
    {
        if(isServer() || isClient())
        {
            Utils.consolePrint(
                "Can't change registry address while it/client mode are enabled"
            );
            return;
        }
        
        if(args == null || args.length != 1)
        {
            printInputKeyUsageString(Inputs.regaddr);
            return;
        }
        
        String[] pair = args[0].split(":", 1);
        if(pair.length != 2)
        {
            printInputKeyUsageString(Inputs.regaddr);
            return;
        }
        
        try
        {
            registryPort = Integer.parseInt(pair[1]);
            registryHost = pair[0];
        }
        catch(NumberFormatException err)
        {
            Utils.consolePrint("Couldn't parse port: %s", err.getMessage());
        }
    }

    private enum Inputs implements Bot.InputKey
    {
        s("Toggle server mode, allowing sending commands to other characters", ""),
        c("Toggle client mode, allowing remote control of this character", ""),
        
        sl("Server: list known clients", ""),
        slr("Server: refresh list of clients", ""),
        sr("Server: dispatch commands", "..."),
        
        regaddr("Set hostname and port of RMI registry", "host:port"),
        ;
        
        
        String description;
        String usage;
        Inputs(String description, String usage) {
            this.description = description;
            this.usage = usage;
        }

        @Override
        public String getName() {
            return name();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getUsage() {
            return usage;
        }
    }
    
    void syncPositionTask()
    {
        final float px = syncPosition ? world.getPlayerPosX() : -1;
        final float py = syncPosition ? world.getPlayerPosY() : -1;
        final float rx = syncHeading ? world.getPlayerRotX() : Float.NaN;
        final float ry = syncHeading ? world.getPlayerRotY() : Float.NaN;
        printExceptions(
            () -> clients.setPosAndHeading(px, py, rx, ry),
            "Got %s when setting clients' position: %s"
        );
        
        if(syncPosition || syncHeading)
            schedule(this::syncPositionTask, syncDelay);
    }
    
    @Override
    public void onClientJoin(String name)
    {
        Utils.consolePrint("Got new client: %s", name);
        printExceptions(
            () -> clients.refreshRemotes(serverRegistry),
            "Got %s when registering that client: %s"
        );
    }
    
    @Override
    public void onClientLeave(String name)
    {
        Utils.consolePrint("Client `%s` disconnected", name);
        printExceptions(
            () -> clients.refreshRemotes(serverRegistry),
            "Got %s when unregistering that client: %s"
        );
    }
    
    @Override
    public String getPlayerName()
    {
        return world.getPlayer().getPlayerName();
    }
    
    @Override
    public void execCmds(String[] cmds) throws RemoteException
    {
        WurmConsole console = world.getClient().getConsole();
        for(int index = 0; index < cmds.length; index++)
            console.handleInput(cmds[index], false);
    }
    
    @Override
    public void genericAction(short actionID, long targetID) throws RemoteException
    {
        PlayerAction action = new PlayerAction(actionID, PlayerAction.ANYTHING, "", false);
        hud.sendAction(action, targetID);
    }
    
    @Override
    public void setPosAndHeading(float x, float y, float rx, float ry) throws RemoteException
    {
        execute(() -> {
            if(x >= 0 && y >= 0)
                Utils.movePlayer(x, y);
            if(!Float.isNaN(rx) && !Float.isNaN(ry))
                Utils.turnPlayer(rx, ry);
        });
    }
    
    @Override
    public void embark(long vehicleID) throws RemoteException
    {
        hud.sendAction(PlayerAction.EMBARK_PASSENGER, vehicleID);
    }
    
    @Override
    public void disembark() throws RemoteException
    {
        CreatureCellRenderable vehicle = world.getPlayer().getCarrierCreature();
        if(vehicle == null)
        {
            Utils.consolePrint("Got disembark request but not riding anything");
            return;
        }
        
        hud.sendAction(PlayerAction.DISEMBARK, vehicle.getId());
    }

    @Override
    public void attack(long creatureID)
    {
        execute(() -> {
            hud.sendAction(PlayerAction.TARGET, creatureID);
            Utils.consolePrint("Attacking creature %s", creatureID);
        });
    }
    
    @Override
    public void dig() throws RemoteException
    {
        if(shovelID == -10)
        {
            InventoryMetaItem tool = Utils.locateToolItem("shovel");
            if(tool == null)
                return;
            else
                shovelID = tool.getId();
        }
        
        final int tx = Math.round(world.getPlayerPosX() / 4);
        final int ty = Math.round(world.getPlayerPosY() / 4);
        final long tileID = Tiles.getTileId(tx, ty, 0);
        world.getServerConnection().sendAction(shovelID, new long[]{tileID}, PlayerAction.DIG);
    }
    
    @Override
    public void level(long tileID) throws RemoteException
    {
        if(shovelID == -10)
        {
            InventoryMetaItem tool = Utils.locateToolItem("shovel");
            if(tool == null)
                return;
            else
                shovelID = tool.getId();
        }
        
        world.getServerConnection().sendAction(shovelID, new long[]{tileID}, PlayerAction.LEVEL);
    }
    
    @Override
    public void mine(long wallID, Direction direction) throws RemoteException
    {
        execute(() -> {
            if(pickaxeID == -10)
            {
                InventoryMetaItem tool = Utils.locateToolItem("pickaxe");
                if(tool == null)
                    return;
                else
                    pickaxeID = tool.getId();
            }
            
            world.getServerConnection().sendAction(
                pickaxeID,
                new long[]{wallID},
                direction.action
            );
        });
    }
    
    @Override
    public void clearCrafting() throws RemoteException
    {
        try
        {
            CreationWindow creationWindow = hud.getCreationWindow();
            CreationFrame source = ReflectionUtil.getPrivateField(
                creationWindow,
                ReflectionUtil.getField(creationWindow.getClass(), "source")
            );
            CreationFrame target = ReflectionUtil.getPrivateField(
                creationWindow,
                ReflectionUtil.getField(creationWindow.getClass(), "target")
            );
            
            source.clearGroundItem();
            target.clearGroundItem();
            source.clearItemList();
            target.clearItemList();
            source.clearTexture();
            target.clearTexture();
        }
        catch(Exception err)
        {
            throw new RemoteException("Failed to clear crafting source/target: ", err);
        }
    }
}

final class ScheduledTask
{
    public Runnable task;
    public long triggerTime;
    
    public ScheduledTask(Runnable task, long triggerTime)
    {
        this.task = task;
        this.triggerTime = triggerTime;
    }
}

interface BotServer extends Remote
{
    void onClientJoin(String name) throws RemoteException;
    void onClientLeave(String name) throws RemoteException;
}

interface BotClient extends Remote
{
    String getPlayerName() throws RemoteException;
    
    void execCmds(String[] consoleCommands) throws RemoteException;
    void genericAction(short actionID, long targetID) throws RemoteException;
    void setPosAndHeading(float x, float y, float rx, float ry) throws RemoteException;
    void embark(long vehicleID) throws RemoteException;
    void disembark(/* tile ID? */) throws RemoteException;
    void attack(long creatureID) throws RemoteException;
    void dig() throws RemoteException;
    void level(long tileID) throws RemoteException;
    void mine(long wallID, MinerBot.Direction direction) throws RemoteException;
    void clearCrafting() throws RemoteException;
}


final class ClientSet implements BotClient
{
    public ArrayList<BotClient> remotes = new ArrayList<>();
    
    public void refreshRemotes(Registry registry) throws Exception
    {
        remotes.clear();
        
        String[] names = registry.list();
        for(String name: names)
        {
            if(!name.startsWith(RMIBot.registryPrefix))
                continue;
            remotes.add((BotClient)registry.lookup(name));
        }
    }
    
    @Override
    public String getPlayerName() throws RemoteException
    {
        String[] names = new String[remotes.size()];
        
        int index = 0;
        for(BotClient remote: remotes)
            names[index++] = remote.getPlayerName();
        
        return String.join(", ", names);
    }
    
    @Override
    public void execCmds(String[] commands) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.execCmds(commands);
    }
    
    @Override
    public void genericAction(short actionID, long targetID) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.genericAction(actionID, targetID);
    }
    
    @Override
    public void setPosAndHeading(float x, float y, float rx, float ry) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.setPosAndHeading(x, y, rx, ry);
    }
    
    @Override
    public void embark(long vehicleID) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.embark(vehicleID);
    }
    
    @Override
    public void disembark() throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.disembark();
    }
    
    @Override
    public void attack(long creatureID) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.attack(creatureID);
    }
    
    @Override
    public void dig() throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.dig();
    }
    
    @Override
    public void level(long tileID) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.level(tileID);
    }
    
    @Override
    public void mine(long wallID, Direction direction) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.mine(wallID, direction);
    }
    
    @Override
    public void clearCrafting() throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.clearCrafting();
    }
}

@FunctionalInterface
interface ThrowingRunnable
{
    void run() throws Exception;
}

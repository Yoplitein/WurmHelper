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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;

import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.client.renderer.gui.TargetWindow;
import com.wurmonline.shared.constants.PlayerAction;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;
import net.ildar.wurm.bot.MinerBot.Direction;

@BotInfo(description = "Remotely control other clients", abbreviation = "rmi")
public class RMIBot extends Bot implements BotRemote, Executor
{
	static final String registryPrefix = "RMIBot";
	String registryHost = "127.0.43.7";
	int registryPort = 0x2B07;
	
	ArrayBlockingQueue<Runnable> oneshotTasks = new ArrayBlockingQueue<>(32, false);
	/*Concurrent?*/HashMap<Runnable, Long> scheduledTasks = new HashMap<>();
	
	// server fields
	MultiRemote clients;
	Registry serverRegistry;
	boolean syncPosition;
	
	// client fields
	Registry clientRegistry;
	
	boolean isServer()
	{
		return serverRegistry != null;
	}
	
	boolean isClient()
	{
		return clientRegistry != null;
	}
	
	boolean printExceptions(ThrowingRunnable fn, String fmt, Object... args)
	{
		try
		{
			fn.run();
			return true;
		}
		catch(Exception err)
		{
			Utils.consolePrint(fmt, err.getClass().getName(), err.getMessage(), args);
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
		Utils.consolePrint("RMI bot cannot be paused");
	}
	
	@Override
	public synchronized void setResumed()
	{
		Utils.consolePrint("RMI bot cannot be paused");
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
		scheduledTasks.put(task, now + msecs);
		notify();
	}
	
	@Override
	void work() throws Exception
	{
		try
		{
			ArrayList<Entry<Runnable, Long>> expiredTasks = new ArrayList<>();
			
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
						for(Entry<Runnable, Long> pair: scheduledTasks.entrySet())
							if(pair.getValue() <= now)
								expiredTasks.add(pair);
						
						for(Entry<Runnable, Long> pair: expiredTasks)
						{
							scheduledTasks.remove(pair.getKey());
							pair.getKey().run();
						}
						
						int oneshotsToRun = oneshotTasks.size(); // process new tasks next iteration
						while(oneshotsToRun-- > 0)
							oneshotTasks.remove().run();
						
						long nextTaskTime = scheduledTasks
							.values()
							.stream()
							.min(Comparator.comparingLong(x -> x))
							.orElse(Long.MAX_VALUE)
						;
						now = System.currentTimeMillis();
						while(oneshotTasks.size() == 0 && nextTaskTime - now > 0)
						{
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
						"RMI: Got %s when running tasks: %s",
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
			}, "Got %s when deactivating RMIBot: %s");
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
				serverRegistry.unbind(name);
			
			clients = null;
			UnicastRemoteObject.unexportObject(serverRegistry, true);
			serverRegistry = null;
			
			Utils.consolePrint("Server mode disabled, RMI registry shut down");
			return;
		}
		
		serverRegistry = LocateRegistry.createRegistry(
			registryPort,
			RMISocketFactory.getDefaultSocketFactory(),
			port -> new ServerSocket(port, 16, InetAddress.getByName(registryHost))
		);
		clients = new MultiRemote();
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
			clientRegistry.unbind(regName);
			UnicastRemoteObject.unexportObject(this, true);
			clientRegistry = null;
			
			Utils.consolePrint("Client mode disabled");
			return;
		}
		
		clientRegistry = LocateRegistry.getRegistry(registryHost, registryPort);
		Remote stub = UnicastRemoteObject.exportObject(this, 0);
		clientRegistry.bind(regName, stub);
		Utils.consolePrint("Client mode enabled");
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
		assert clients != null;
		
		if(args.length == 0)
		{
			Utils.consolePrint("Must specify a subcommand");
			return;
		}
		
		
		final HeadsUpDisplay hud = WurmHelper.hud;
		final World world = hud.getWorld();
		switch(args[0].toLowerCase())
		{
			case "attack":
				final String attackSrc = args.length >= 2 ? args[1] : "hover";
				switch(attackSrc)
				{
					case "target":
						TargetWindow targetWin = ReflectionUtil.getPrivateField(
							hud,
							ReflectionUtil.getField(
								hud.getClass(),
								"targetWindow"
							)
						);
						CreatureCellRenderable targetCreature = ReflectionUtil.getPrivateField(
							targetWin,
							ReflectionUtil.getField(
								targetWin.getClass(),
								"creature"
							)
						);
						
						clients.attack(targetCreature.getId());
						break;
					case "hover":
						PickableUnit unit = world.getCurrentHoveredObject();
						if(unit == null || !(unit instanceof CreatureCellRenderable))
						{
							Utils.consolePrint("Not hovering over creature");
							break;
						}
						
						attack(unit.getId());
						clients.attack(unit.getId());
						break;
					default:
						Utils.consolePrint("Usage: %s attack [hover|target]", Inputs.sr.getName());
				}
				break;
			case "syncpos":
				syncPosition = !syncPosition;
				if(syncPosition)
					execute(this::syncPositionTask);
				
				Utils.consolePrint(
					"Position synchronization %s",
					syncPosition ? "on" : "off"
				);
				break;
			case "embark":
				final String embarkSrc = args.length >= 2 ? args[1] : "hover";
				switch(embarkSrc)
				{
					case "riding":
						// TODO
						break;
					case "hover":
						PickableUnit unit = world.getCurrentHoveredObject();
						if(unit == null || !(unit instanceof CreatureCellRenderable))
						{
							Utils.consolePrint("Not hovering over vehicle");
							break;
						}
						
						long id = unit.getId();
						WurmHelper.hud.sendAction(PlayerAction.EMBARK_DRIVER, id);
						clients.embark(id);
						break;
					default:
						Utils.consolePrint("Usage: %s embark [riding|hover]");
				}
				break;
			case "disembark":
				disembark();
				clients.disembark();
				break;
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
		
		if(args.length != 1)
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
		World world = WurmHelper.hud.getWorld();
		final float px = world.getPlayerPosX();
		final float py = world.getPlayerPosY();
		final float rx = world.getPlayerRotX();
		final float ry = world.getPlayerRotY();
		
		printExceptions(
			() -> clients.setPosAndHeading(px, py, rx, ry),
			"Got %s when setting clients' position: %s"
		);
		
		if(syncPosition)
			schedule(this::syncPositionTask, 100);
	}
	
	@Override
	public String getPlayerName()
	{
		return WurmHelper.hud.getWorld().getPlayer().getPlayerName();
	}

	@Override
	public void setPosAndHeading(float x, float y, float rx, float ry)
	{
		execute(() -> {
			Utils.movePlayer(x, y);
			Utils.turnPlayer(rx, ry);
		});
	}
	
	@Override
	public void embark(long vehicleID) throws RemoteException
	{
		WurmHelper.hud.sendAction(PlayerAction.EMBARK_PASSENGER, vehicleID);
	}
	
	@Override
	public void disembark() throws RemoteException
	{
		CreatureCellRenderable vehicle = WurmHelper.hud.getWorld().getPlayer().getCarrierCreature();
		if(vehicle == null)
		{
			Utils.consolePrint("Got disembark request but not riding anything");
			return;
		}
		
		WurmHelper.hud.sendAction(PlayerAction.DISEMBARK, vehicle.getId());
	}

	@Override
	public void attack(long creatureID)
	{
		execute(() -> {
			WurmHelper.hud.sendAction(PlayerAction.TARGET, creatureID);
			Utils.consolePrint("Attacking creature %s", creatureID);
		});
	}

	@Override
	public void mine(long tileID, Direction direction)
	{
		execute(() -> {
			// TODO
		});
	}
}

interface BotRemote extends Remote
{
	String getPlayerName() throws RemoteException;
	
	void setPosAndHeading(float x, float y, float rx, float ry) throws RemoteException;
	void embark(long vehicleID) throws RemoteException;
	void disembark(/* tile ID? */) throws RemoteException;
	void attack(long creatureID) throws RemoteException;
	void mine(long tileID, MinerBot.Direction direction) throws RemoteException;
}

final class MultiRemote implements BotRemote
{
	ArrayList<BotRemote> remotes = new ArrayList<>();
	
	public void refreshRemotes(Registry registry) throws Exception
	{
		remotes.clear();
		
		String[] names = registry.list();
		for(String name: names)
		{
			if(!name.startsWith(RMIBot.registryPrefix))
				continue;
			remotes.add((BotRemote)registry.lookup(name));
		}
	}
	
	@Override
	public String getPlayerName() throws RemoteException
	{
		String[] names = new String[remotes.size()];
		
		int index = 0;
		for(BotRemote remote: remotes)
			names[index++] = remote.getPlayerName();
		
		return String.join(", ", names);
	}

	@Override
	public void setPosAndHeading(float x, float y, float rx, float ry) throws RemoteException
	{
		for(BotRemote remote: remotes)
			remote.setPosAndHeading(x, y, rx, ry);
	}
	
	@Override
	public void embark(long vehicleID) throws RemoteException
	{
		for(BotRemote remote: remotes)
			remote.embark(vehicleID);
	}
	
	@Override
	public void disembark() throws RemoteException
	{
		for(BotRemote remote: remotes)
			remote.disembark();
	}
	
	@Override
	public void attack(long creatureID) throws RemoteException
	{
		for(BotRemote remote: remotes)
			remote.attack(creatureID);
	}

	@Override
	public void mine(long tileID, Direction direction) throws RemoteException
	{
		for(BotRemote remote: remotes)
			remote.mine(tileID, direction);
	}
}

@FunctionalInterface
interface ThrowingRunnable
{
	void run() throws Exception;
}

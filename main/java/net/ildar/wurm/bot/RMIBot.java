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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;

import com.wurmonline.client.comm.SimpleServerConnectionClass;
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
	
	ArrayBlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(32, false);
	
	// server fields
	MultiRemote clients;
	Registry serverRegistry;
	
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
	public void execute(Runnable task)
	{
		tasks.add(task);
	}
	
	@Override
	void work() throws Exception
	{
		try
		{
			while(isActive())
			{
				try
				{
					Runnable task = tasks.take();
					task.run();
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
			
			Utils.consolePrint("Server mode disabled, registry shut down");
			return;
		}
		
		serverRegistry = LocateRegistry.createRegistry(
			registryPort,
			RMISocketFactory.getDefaultSocketFactory(),
			port -> new ServerSocket(port, 16, InetAddress.getByName(registryHost))
		);
		clients = new MultiRemote();
		Utils.consolePrint("Server mode active, registry running at %s:%d", registryHost, registryPort);
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
			
			return;
		}
		
		clientRegistry = LocateRegistry.getRegistry(registryHost, registryPort);
		Remote stub = UnicastRemoteObject.exportObject(this, 0);
		clientRegistry.bind(regName, stub);
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
				final String src = args.length >= 2 ? args[1] : "target";
				if(src.equalsIgnoreCase("target"))
				{
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
				}
				else if(src.equalsIgnoreCase("hover"))
				{
					PickableUnit unit = world.getCurrentHoveredObject();
					if(unit == null || !(unit instanceof CreatureCellRenderable))
					{
						Utils.consolePrint("Not hovering over creature");
						break;
					}
					
					attack(unit.getId());
					clients.attack(unit.getId());
				}
				else
					Utils.consolePrint("Usage: %s attack <target|hover>", Inputs.sr.getName());
				
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

	@Override
	public String getPlayerName()
	{
		return WurmHelper.hud.getWorld().getPlayer().getPlayerName();
	}

	@Override
	public void moveTo(float x, float y)
	{
		execute(() -> {
			Utils.movePlayer(x, y);
		});
	}

	@Override
	public void attack(long creatureID)
	{
		execute(() -> {
			WurmHelper
				.hud
				.getWorld()
				.getServerConnection()
				.sendAction(-10, new long[]{creatureID}, PlayerAction.TARGET)
			;
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
	
	void moveTo(float x, float y) throws RemoteException;
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
	public void moveTo(float x, float y) throws RemoteException
	{
		for(BotRemote remote: remotes)
			remote.moveTo(x, y);
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

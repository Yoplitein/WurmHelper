package net.ildar.wurm.bot;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

import com.wurmonline.client.comm.ServerConnectionListenerClass;
import com.wurmonline.client.game.NearTerrainDataBuffer;
import com.wurmonline.client.game.PlayerObj;
import com.wurmonline.client.game.TerrainDataBuffer;
import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.client.renderer.structures.FenceData;
import com.wurmonline.client.renderer.structures.HouseData;
import com.wurmonline.client.renderer.structures.HouseWallData;
import com.wurmonline.client.renderer.structures.StructureData;
import com.wurmonline.math.Vector2f;
import com.wurmonline.shared.constants.StructureTypeEnum;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;

@BotInfo(abbreviation = "pt", description = "Bot that can perform pathfinding to accomplish its various tasks")
public class PathingBot extends Bot
{
	final ForkJoinPool pool = new ForkJoinPool(
		8,
		new ForkJoinPool.ForkJoinWorkerThreadFactory()
		{
			int counter = 0;
			
			public ForkJoinWorkerThread newThread(ForkJoinPool pool)
			{
				ForkJoinWorkerThread thread = new WorkerThread(pool);
				thread.setName(String.format(
					"%s-worker-%s",
					PathingBot.class.getSimpleName(),
					counter++
				));
				return thread;
			}
		},
		(thread, err) -> {
			StringWriter writer = new StringWriter();
			err.printStackTrace(new PrintWriter(writer));
			Utils.consolePrint(
				"%s: uncaught exception in task:\n%s",
				PathingBot.class.getSimpleName(),
				writer.toString()
			);
		},
		true
	);
	
	final HeadsUpDisplay hud = WurmHelper.hud;
    final World world = hud.getWorld();
	final PlayerObj player = world.getPlayer();
	
	boolean exiting = false;
	
	static final float tickDelta = 1 / 5f;
	float topSpeedMPS = (15f * 1000) / 60 / 60;
	
	InputHandler inPool(InputHandler fn)
	{
		return args -> pool.execute(() -> fn.handle(args));
	}
	
	boolean waitOnPauseFJ()
	{
		try
		{
			ForkJoinPool.managedBlock(new PauseBlocker(this));
			return true;
		}
		catch(InterruptedException err) { return false; }
	}
	
	public PathingBot()
	{
		registerInputHandler(Inputs.speed, inPool(this::cmdSpeed));
		registerInputHandler(Inputs.walkto, inPool(this::cmdWalkto));
	}
	
	void cmdSpeed(String[] args)
	{
		if(args == null || args.length == 0)
		{
			printInputKeyUsageString(Inputs.speed);
			return;
		}
		
		float newSpeed = Float.parseFloat(args[0]);
		topSpeedMPS = (newSpeed * 1000) / 60 / 60;
		Utils.consolePrint(
			"Bot will now move at %f kph (%f m/s)",
			newSpeed,
			topSpeedMPS
		);
	}
	
	void cmdWalkto(String[] args)
	{
		if(args == null || args.length < 2)
		{
			printInputKeyUsageString(Inputs.walkto);
			return;
		}
		
		int tx = Integer.parseInt(args[0]);
		int ty = Integer.parseInt(args[1]);
		Utils.consolePrint("pathfinding to %d,%d", tx, ty);
		if(!walkPath(tx, ty))
			Utils.consolePrint("Couldn't find a path/interrupted");
	}
	
	@Override
	void work() throws Exception
	{
		try
		{
			while(isActive())
				// doesn't seem to be a way to donate this thread to the pool
				// so we just sleep I guess
				Thread.sleep(Long.MAX_VALUE);
		}
		catch(InterruptedException err) {}
		finally
		{
			exiting = true;
			pool.shutdown();
			if(!pool.awaitTermination(10, TimeUnit.SECONDS))
				Utils.consolePrint(
					"%s: task pool did not shut down within 10 seconds",
					PathingBot.class.getSimpleName()
				);
		}
		
	}
	
	// move in straight line from current tile to given tile
	// returns whether walking finished without interruption
	boolean walkLine(int tileX, int tileY)
	{
		// always move from center to center
		Utils.moveToCenter();
		
		Vector2f dest = new Vector2f(tileX * 4 + 2, tileY * 4 + 2);
		Vector2f plyPos = new Vector2f(world.getPlayerPosX(), world.getPlayerPosY());
		Vector2f totalMovement = dest.subtract(plyPos);
		float dist = totalMovement.length();
		
		Vector2f direction = totalMovement.normalize();
		Vector2f step = new Vector2f();
		
		while(!exiting && dist > 0)
		{
			if(!waitOnPauseFJ())
				return false;
			
			float toMove = Math.min(dist, topSpeedMPS * tickDelta);
			dist -= toMove;
			direction.mult(toMove, step);
			plyPos.addLocal(step);
			Utils.movePlayer(plyPos.x, plyPos.y);
			
			try { ForkJoinPool.managedBlock(new SleepBlocker((long)(1000 * tickDelta))); }
			catch(InterruptedException err) { return false; }
		}
		return true;
	}
	
	// finds and then moves along an unobstructed path from current tile to given tile
	// returns false if a path could not be found, or walking was interrupted
	boolean walkPath(int tileX, int tileY)
	{
		try
		{
			final Collection<Vec2i> path = findPath(tileX, tileY);
			Utils.consolePrint("path: %s", path);
			if(path == null) return false;
			for(Vec2i tileCoords: path)
				if(!walkLine(tileCoords.x, tileCoords.y))
					return false;
			
			return true;
		}
		catch(Exception err)
		{
			Utils.consolePrint("Uncaught %s in walkPath: %s", err.getClass().getName(), err.getMessage());
			err.printStackTrace();
			return false;
		}
	}
	
	ArrayList<Vec2i> findPath(int tileX, int tileY)
	{
		final long startTime = System.nanoTime();
		final Vec2i startTile = new Vec2i(world.getPlayerCurrentTileX(), world.getPlayerCurrentTileY());
		final Vec2i endTile = new Vec2i(tileX, tileY);
		CollisionCache cache = new CollisionCache();
		cache.refresh();
		
		HashMap<Vec2i, PathNode> populated = new HashMap<>();
		HashSet<Vec2i> enqueued = new HashSet<>(); // set of coords in queue, for faster search
		PriorityQueue<PathNode> queue = new PriorityQueue<>(PathNode::compare);
		queue.add(new PathNode(startTile, 0, endTile, null));
		PathNode endNode = null;
		while(!queue.isEmpty())
		{
			PathNode head = queue.poll();
			if(head.pos.equals(endTile))
			{
				endNode = head;
				break;
			}
			enqueued.remove(head.pos);
			
			for(Dir dir: Dir.values())
			{
				final Vec2i neighborTile = new Vec2i(head.pos.x + dir.offset.x, head.pos.y + dir.offset.y);
				if(!cache.isPassable(neighborTile.x, neighborTile.y, Dir.all))
					continue;
				
				PathNode neighbor;
				final boolean edgePassable = cache.isPassable(head.pos.x, head.pos.y, dir);
				final int cost = edgePassable ? head.distFromStart + 1 : Integer.MAX_VALUE;
				if(!populated.containsKey(neighborTile))
				{
					neighbor = new PathNode(neighborTile, cost, endTile, head);
					populated.put(neighborTile, neighbor);
					if(edgePassable)
					{
						queue.add(neighbor);
						enqueued.add(neighbor.pos);
					}
				}
				else
					neighbor = populated.get(neighborTile);
				
				if(edgePassable && cost < neighbor.distFromStart)
				{
					neighbor.distFromStart = cost;
					neighbor.previous = head;
					if(!enqueued.contains(neighborTile))
					{
						queue.add(neighbor);
						enqueued.add(neighborTile);
					}
				}
			}
		}
		
		if(endNode == null)
		{
			Utils.consolePrint("Couldn't find viable path");
			return null;
		}
		
		ArrayList<Vec2i> path = new ArrayList<>();
		PathNode node = endNode;
		while(node != null)
		{
			path.add(node.pos);
			node = node.previous;
		}
		Collections.reverse(path);
		final long endTime = System.nanoTime();
		Utils.consolePrint("Found path in %.4fms", (endTime - startTime) / 1_000_000.0);
		return path;
	}
	
	enum Inputs implements Bot.InputKey
	{
		speed("Set speed at which bot will move, in km/h", "real"),
		walkto("Walk to given tile coordinates", "x,y"),
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
}

// unnecessarily protected ctor :facepalm:
class WorkerThread extends ForkJoinWorkerThread
{
	public WorkerThread(ForkJoinPool pool)
	{
		super(pool);
	}
}

// that this isn't in the stdlib is typical Java >:|
class SleepBlocker implements ForkJoinPool.ManagedBlocker
{
	long wakeTimeMS;
	
	public SleepBlocker(long durationMS)
	{
		wakeTimeMS = System.currentTimeMillis() + durationMS;
	}
	
	long remaining()
	{
		return wakeTimeMS - System.currentTimeMillis();
	}
	
	@Override
	public boolean block() throws InterruptedException
	{
		Thread.sleep(remaining());
		return remaining() <= 0;
	}

	@Override
	public boolean isReleasable()
	{
		return remaining() <= 0;
	}
}

class PauseBlocker implements ForkJoinPool.ManagedBlocker
{
	PathingBot bot;
	public PauseBlocker(PathingBot bot)
	{
		this.bot = bot;
	}
	
	@Override
	public boolean block() throws InterruptedException
	{
		bot.wait();
		return !bot.getPaused();
	}

	@Override
	public boolean isReleasable()
	{
		return !bot.getPaused();
	}
}

class Vec2i
{
	public int x;
	public int y;
	
	public Vec2i()
	{
		x = 0;
		y = 0;
	}
	
	public Vec2i(int x, int y)
	{
		this.x = x;
		this.y = y;
	}
	
	@Override
	public int hashCode()
	{
		return 31 * Integer.hashCode(x) + Integer.hashCode(y);
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if(this == obj) return true;
		if(obj == null || !(obj instanceof Vec2i)) return false;
		Vec2i rhs = (Vec2i)obj;
		return x == rhs.x && y == rhs.y;
	}
	
	@Override
	public String toString()
	{
		return String.format("Vec2i(%d, %d)", x, y);
	}
}

enum Dir
{
	all(new Vec2i(0, 0)),
	north(new Vec2i(0, -1)),
	east(new Vec2i(1, 0)),
	south(new Vec2i(0, 1)),
	west(new Vec2i(-1, 0));
	
	Vec2i offset;
	Dir(Vec2i offset)
	{
		this.offset = offset;
	}
}

class PathNode
{
	public Vec2i pos;
	public int distFromStart;
	public double distToEnd;
	public PathNode previous = null;
	
	public PathNode(Vec2i pos, int distFromStart, Vec2i goal, PathNode previous)
	{
		this.pos = pos;
		this.distFromStart = distFromStart;
		this.distToEnd = Math.pow(pos.x - goal.x, 2) + Math.pow(pos.y - goal.y, 2);
		this.previous = previous;
	}

	public double cost()
	{
		return distFromStart + distToEnd;
	}
	
	public static int compare(PathNode lhs, PathNode rhs)
	{
		return Double.compare(lhs.cost(), rhs.cost());
	}
}

class CollisionCache
{
	static final int pvsDiameter = 512;
	static final int pvsRadius = pvsDiameter >> 1;
	static final byte flagNorth = 1 << 0;
	static final byte flagWest = 1 << 1;
	static final byte flagTile = 1 << 2;
	
	World world;
	ServerConnectionListenerClass serverConnection;
	Map<Long, StructureData> structures;
	TerrainDataBuffer groundBuffer;
	
	byte[] cache;
	Vec2i origin;
	Vec2i mins;
	Vec2i maxs;
	
	public CollisionCache()
	{
		world = WurmHelper.hud.getWorld();
		serverConnection = world.getServerConnection().getServerConnectionListener();
		structures = Utils.rethrow(() -> Utils.getField(serverConnection, "structures"));
		groundBuffer = world.getNearTerrainBuffer();	
		
		cache = new byte[pvsDiameter * pvsDiameter];
	}
	
	public void refresh()
	{
		origin = new Vec2i(world.getPlayerCurrentTileX(), world.getPlayerCurrentTileY());
		mins = new Vec2i(origin.x - pvsRadius, origin.y - pvsRadius);
		maxs = new Vec2i(origin.x + pvsRadius, origin.y + pvsRadius);
		
		for(int i = 0; i < cache.length; i++)
			cache[i] = 0;
		
		for(int y = mins.y; y < maxs.y; y++)
			for(int x = mins.x; x < maxs.x; x++)
				if(!isTilePassable(x, y))
					insert(x, y, -1);
		
		for(StructureData v: structures.values())
		{
			if(v instanceof FenceData)
				insert((FenceData)v);
			else if(v instanceof HouseData)
				insert((HouseData)v);
		}
	}
	
	public boolean isPassable(int tileX, int tileY, Dir direction)
	{
		final Vec2i pos = posInPVS(tileX, tileY);
		final int index = pos.y * pvsDiameter + pos.x;
		final byte flags = cache[index];
		switch(direction)
		{
			case all:
				return (flags & flagTile) == 0;
			case north:
				return (flags & flagNorth) == 0;
			case west:
				return (flags & flagWest) == 0;
			
			// collision data is only recorded for north/west, east/south must check
			// opposite borders of adjacent tile
			case east:
				try { return isPassable(tileX + Dir.east.offset.x, tileY, Dir.west); }
				catch(IllegalArgumentException err)
				{
					Utils.consolePrint(err.getMessage());
					return false;
				}
			case south:
				try { return isPassable(tileX, tileY + Dir.south.offset.y, Dir.north); }
				catch(IllegalArgumentException err)
				{
					Utils.consolePrint(err.getMessage());
					return false;
				}
			
			default:
				throw new RuntimeException("unknown direction");
		}
	}
	
	void insert(FenceData fence)
	{
		// for now pathing is restricted to layer 0
		if(fence.getRealHeight() != 0)
			return;
		
		if(isPassableStructure(fence.getType().type))
			return;
		
		insert(fence.getTileX(), fence.getTileY(), fence.getDir());
		
		// for debugging
		/* final Vec2i pos = new Vec2i(fence.getTileX(), fence.getTileY());
		Utils.consolePrint(
			"inserting fence `%s` at %s with direction `%s` and type `%s`",
			fence.getModel().getModelData().getUrl(),
			pos,
			fence.getDir(),
			fence.getType().type
		); */
	}
	
	void insert(HouseData house)
	{
		Map<Long, HouseWallData> walls = Utils.rethrow(() -> Utils.getField(house, "walls"));
		for(HouseWallData wall: walls.values())
			insert(wall);
	}
	
	void insert(HouseWallData wall)
	{
		// for now pathing is restricted to layer 0
		if(wall.getRealHeight() != 0)
			return;
		
		if(isPassableStructure(wall.getType().type))
			return;
		
		insert(wall.getTileX(), wall.getTileY(), wall.getDir());
		
		// for debugging
		/* final Vec2i pos = new Vec2i(wall.getTileX(), wall.getTileY());
		Utils.consolePrint(
			"inserting house wall `%s` at %s with direction `%s` and type `%s`",
			wall.getModel().getModelData().getUrl(),
			pos,
			wall.getDir(),
			wall.getType().type
		); */
	}
	
	void insert(int tileX, int tileY, int wurmDirection)
	{
		if(wurmDirection < 0)
		{
			setFlags(tileX, tileY, flagTile);
			return;
		}
		
		final boolean north = wurmDirection == 0;
		setFlags(tileX, tileY, north ? flagNorth : flagWest);
	}
	
	void setFlags(int tileX, int tileY, int newFlags)
	{
		final Vec2i pos = posInPVS(tileX, tileY);
		final int index = pos.y * pvsDiameter + pos.x;
		byte flags = cache[index];
		flags |= newFlags;
		cache[index] = flags;
	}
	
	Vec2i posInPVS(int tileX, int tileY)
	{
		Vec2i pos = new Vec2i((tileX - origin.x) + pvsRadius, (tileY - origin.y) + pvsRadius);
		if(pos.x < 0 || pos.x >= pvsDiameter || pos.y < 0 || pos.y >= pvsDiameter)
			throw new IllegalArgumentException(String.format(
				"Trying to get position %s (local %s) that is out of bounds for PVS at %s (from %s to %s)",
				new Vec2i(tileX, tileY),
				pos,
				origin,
				mins,
				maxs
			));
		return pos;
	}
	
	boolean isPassableStructure(StructureTypeEnum type)
	{
		switch(type)
		{
			default:
				return false;
			case ARCHED:
			case ARCHED_LEFT:
			case ARCHED_RIGHT:
			case ARCHED_T:
			case CURB:
			case FENCE_PLAN_CRUDE:
			case FENCE_PLAN_CRUDE_GATE:
			case FENCE_PLAN_CURB:
			case FENCE_PLAN_GARDESGARD_GATE:
			case FENCE_PLAN_GARDESGARD_HIGH:
			case FENCE_PLAN_GARDESGARD_LOW:
			case FENCE_PLAN_IRON_BARS:
			case FENCE_PLAN_IRON_BARS_GATE:
			case FENCE_PLAN_IRON_BARS_PARAPET:
			case FENCE_PLAN_IRON_BARS_TALL:
			case FENCE_PLAN_IRON_BARS_TALL_GATE:
			case FENCE_PLAN_MEDIUM_CHAIN:
			case FENCE_PLAN_PALISADE:
			case FENCE_PLAN_PALISADE_GATE:
			case FENCE_PLAN_PORTCULLIS:
			case FENCE_PLAN_ROPE_HIGH:
			case FENCE_PLAN_ROPE_LOW:
			case FENCE_PLAN_STONE_FENCE:
			case FENCE_PLAN_STONE_PARAPET:
			case FENCE_PLAN_STONEWALL:
			case FENCE_PLAN_STONEWALL_HIGH:
			case FENCE_PLAN_WOODEN:
			case FENCE_PLAN_WOODEN_GATE:
			case FENCE_PLAN_WOODEN_PARAPET:
			case FENCE_PLAN_WOVEN:
			case FLOWERBED:
			case HEDGE_LOW:
			case HOUSE_PLAN_ARCH_LEFT:
			case HOUSE_PLAN_ARCH_RIGHT:
			case HOUSE_PLAN_ARCH_T:
			case HOUSE_PLAN_ARCHED:
			case HOUSE_PLAN_BALCONY:
			case HOUSE_PLAN_BARRED:
			case HOUSE_PLAN_CANOPY:
			case HOUSE_PLAN_DOOR:
			case HOUSE_PLAN_DOUBLE_DOOR:
			case HOUSE_PLAN_JETTY:
			case HOUSE_PLAN_NARROW_WINDOW:
			case HOUSE_PLAN_ORIEL:
			case HOUSE_PLAN_PORTCULLIS:
			case HOUSE_PLAN_SOLID:
			case HOUSE_PLAN_WINDOW:
			case NO_WALL:
			case PLAN:
				return true;
		}
	}
	
	boolean isTilePassable(int tileX, int tileY)
	{
		switch(groundBuffer.getTileType(tileX, tileY))
		{
			case TILE_BUSH_THORN:
			case TILE_LAVA:
			case TILE_HOLE: // cave entrance without door
			case TILE_MINE_DOOR_GOLD:
			case TILE_MINE_DOOR_SILVER:
			case TILE_MINE_DOOR_STEEL:
			case TILE_MINE_DOOR_STONE:
			case TILE_MINE_DOOR_WOOD:
				return false;
			default:
		}
		
		NearTerrainDataBuffer terrain = world.getNearTerrainBuffer();
		final float nw = terrain.getHeight(tileX, tileY);
		final float ne = terrain.getHeight(tileX + 1, tileY);
		final float sw = terrain.getHeight(tileX, tileY + 1);
		final float se = terrain.getHeight(tileX + 1, tileY + 1);
		
		// if(tileX == world.getPlayerCurrentTileX() && tileY == world.getPlayerCurrentTileY())
		// 	Utils.consolePrint("nw %.4f ne %.4f sw %.4f se %.4f", nw, ne, sw, se);
		
		final float maxSlope = 22 / 10f;
		final float minHeight = -15 / 10f;
		if(absdiff(nw, ne) > maxSlope || absdiff(nw, sw) > maxSlope)
			return false;
		if(absdiff(se, ne) > maxSlope || absdiff(se, sw) > maxSlope)
			return false;
		if(absdiff(nw, se) > maxSlope || absdiff(ne, sw) > maxSlope)
			return false;
		if(Math.max(Math.max(nw, ne), Math.max(sw, se)) < minHeight)
			return false;
		
		return true;
	}
	
	static float absdiff(float lhs, float rhs)
	{
		return Math.abs(lhs - rhs);
	}
}

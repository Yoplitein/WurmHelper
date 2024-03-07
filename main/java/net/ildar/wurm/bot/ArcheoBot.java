package net.ildar.wurm.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.client.renderer.gui.InventoryListComponent;
import com.wurmonline.client.renderer.gui.InventoryWindow;
import com.wurmonline.client.renderer.gui.ItemListWindow;
import com.wurmonline.client.renderer.gui.WurmComponent;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.shared.constants.PlayerAction;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.Utils.Vec2i;
import net.ildar.wurm.annotations.BotInfo;

@BotInfo(
	description = "Investigates tiles and identifies fragments",
	abbreviation = "ac"
)
public class ArcheoBot extends Bot {
	static final int identifyChisel = 1201;
	static final int identifyBrush = 882;

	static final Pattern fragmentRegex = Pattern.compile("unidentified .*fragment");

	boolean investigating = false;
	boolean identifying = false;
	boolean useShovel = false;

	long trowelId = -1;
	long shovelId = -1;
	long chiselId = -1;
	long brushId = -1;

	List<InventoryListComponent> identifyInventories = new ArrayList<>();

	AreaAssistant areaAssistant = new AreaAssistant(this);

	boolean investigatingDone = false;
	
	boolean fragmentIdentified = false;

	public ArcheoBot() {
		registerInputHandler(InputKey.iv, this::toggleInvestigating);
		registerInputHandler(InputKey.id, this::toggleIdentifying);
		registerInputHandler(InputKey.sh, this::toggleUseShovel);
		registerInputHandler(InputKey.at, this::setIdentifyInventory);
		registerInputHandler(InputKey.ct, this::clearIdentifyInventory);

		registerEventProcessor(
			msg -> {
				msg = msg.toLowerCase();
				return
					msg.contains("the area looks picked clean") ||
					msg.contains("you pick out a fragment of some item") ||
					msg.contains("you can't find any traces of any abandoned settlements here")
				;
			},
			() -> investigatingDone = true
		);

		registerEventProcessor(
			msg -> msg.toLowerCase().contains("you successfully identify the fragment"),
			() -> fragmentIdentified = true
		);

		areaAssistant.setMoveAheadDistance(3);
		areaAssistant.setMoveRightDistance(3);
	}

	@Override
	public void work() throws Exception {
		try {
			trowelId = Utils.locateToolItem("trowel").getId();
			shovelId = Utils.locateToolItem("shovel").getId();
			chiselId = Utils.locateToolItem("chisel").getId();
			brushId = Utils.locateToolItem("metal brush").getId();
		} catch(NullPointerException err) {
			Utils.consolePrint("Missing required archaeology tools");
			return;
		}

		Set<Vec2i> tilesInvestigated = new HashSet<>();
		List<Vec2i> tilesToBeInvestigated = new ArrayList<>();

		List<InventoryMetaItem> unidentifiedFragments = new ArrayList<>();

		outer: while(isActive()) {
			waitOnPause();

			if(investigating) {
				if(tilesToBeInvestigated.isEmpty()) {
					final Vec2i curPos = new Vec2i(
						WurmHelper.hud.getWorld().getPlayerCurrentTileX(),
						WurmHelper.hud.getWorld().getPlayerCurrentTileY()
					);
					for(int y = -1; y < 2; y++)
						for(int x = -1; x < 2; x++) {
							final Vec2i next = new Vec2i(curPos.x + x,curPos.y + y);
							if(tilesInvestigated.contains(next))
								continue;
							tilesToBeInvestigated.add(next);
						}
					
					if(tilesToBeInvestigated.isEmpty()) {
						Thread.sleep(1000);
						areaAssistant.areaNextPosition();
						continue outer;
					}
				}

				if(!tilesToBeInvestigated.isEmpty()) {
					investigatingDone = false;
					
					final Vec2i tile = tilesToBeInvestigated.get(tilesToBeInvestigated.size() - 1);
					final long tool = useShovel ? shovelId : trowelId;
					WurmHelper.hud.getWorld().getServerConnection().sendAction(
						tool,
						new long[]{Tiles.getTileId(tile.x, tile.y, 0)},
						PlayerAction.INVESTIGATE
					);

					if(waitActionStarted(() -> investigatingDone))
						waitActionFinished();
					if(investigatingDone) {
						tilesToBeInvestigated.remove(tilesToBeInvestigated.size() - 1);
						tilesInvestigated.add(tile);
					}
				}
			}

			if(identifying) {
				if(unidentifiedFragments.isEmpty()) {
					for(InventoryListComponent ilc: identifyInventories) {
						List<InventoryMetaItem> found = Utils.getInventoryItems(
							ilc,
							item -> fragmentRegex.matcher(item.getDisplayName()).find()
						);
						if(found != null)
							unidentifiedFragments.addAll(found);
					}

					if(unidentifiedFragments.isEmpty()) {
						Thread.sleep(1000);
						continue outer;
					}
				}

				if(!unidentifiedFragments.isEmpty()) {
					fragmentIdentified = false;

					final InventoryMetaItem fragment = unidentifiedFragments.get(unidentifiedFragments.size() - 1);
					int improveIcon = fragment.getImproveIconId();
					if(improveIcon == identifyChisel) {
						WurmHelper.hud.getWorld().getServerConnection().sendAction(
							chiselId,
							new long[]{fragment.getId()},
							PlayerAction.IDENTIFY
						);
					} else if(improveIcon == identifyBrush) {
						WurmHelper.hud.getWorld().getServerConnection().sendAction(
							brushId,
							new long[]{fragment.getId()},
							PlayerAction.IDENTIFY
						);
					} else {
						Utils.consolePrint("Don't know how to identify fragment `%s`!", fragment.getDisplayName());
						unidentifiedFragments.remove(unidentifiedFragments.size() - 1);
						continue outer;
					}
					
					if(!waitActionStarted(null))
						continue outer;
					waitActionFinished();
					if(fragmentIdentified)
						unidentifiedFragments.remove(unidentifiedFragments.size() - 1);
				}
			}
		}
	}

	void toggleInvestigating(String[] input) {
		investigating = !investigating;
		Utils.consolePrint("Bot will%s investigate", investigating ? "" : " no longer");
	}

	void toggleIdentifying(String[] input) {
		identifying = !identifying;
		Utils.consolePrint("Bot will%s identify fragments", identifying ? "" : " no longer");
	}

	void toggleUseShovel(String[] input) {
		useShovel = !useShovel;
		Utils.consolePrint("Bot will use %s to investigate", useShovel ? "shovel" : "trowel");
	}

	void setIdentifyInventory(String[] input) {
		WurmComponent inventoryComponent = Utils.getTargetComponent(c -> c instanceof ItemListWindow || c instanceof InventoryWindow);
        if (inventoryComponent == null) {
            Utils.consolePrint("Couldn't find an inventory under the cursor");
            return;
        }
        InventoryListComponent ilc;
        try {
            ilc = ReflectionUtil.getPrivateField(inventoryComponent,
                    ReflectionUtil.getField(inventoryComponent.getClass(), "component"));
        } catch(Exception e) {
            Utils.consolePrint("Unable to get inventory information");
            return;
        }
        identifyInventories.add(ilc);
        Utils.consolePrint("Bot will now identify fragments in %s", ilc.name);
	}

	void clearIdentifyInventory(String[] input) {
		identifyInventories.clear();
		Utils.consolePrint("Fragment inventories cleared");
	}

	boolean waitActionStarted(Supplier<Boolean> failed) throws Exception {
		CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
        Object progressBar = ReflectionUtil.getPrivateField(creationWindow, ReflectionUtil.getField(creationWindow.getClass(), "progressBar"));
		final long start = System.currentTimeMillis();
		while(true) {
			if(failed != null && failed.get()) {
				return false;
			}

			float progress = ReflectionUtil.getPrivateField(progressBar, ReflectionUtil.getField(progressBar.getClass(), "progress"));
			if(progress != 0f) {
				return true;
			}
			Thread.sleep(250);

			final long now = System.currentTimeMillis();
			if(now - start > 10_000) {
				Utils.consolePrint("Timed out waiting for action to start");
				return false;
			}
		}
	}
	
	void waitActionFinished() throws Exception {
		CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
        Object progressBar = ReflectionUtil.getPrivateField(creationWindow, ReflectionUtil.getField(creationWindow.getClass(), "progressBar"));
		while(true) {
			float progress = ReflectionUtil.getPrivateField(progressBar, ReflectionUtil.getField(progressBar.getClass(), "progress"));
			if(progress == 0f) {
				break;
			}
			Thread.sleep(250);
		}
	}

	enum InputKey implements Bot.InputKey {
		iv("Toggle investigating", ""),
		id("Toggle identifying", ""),
		sh("Toggle investigating with shovel", ""),
		at("Add target inventory to identify fragments in", ""),
		ct("Clear inventories to identify fragments in", ""),
		;

		public String description;
        public String usage;
        InputKey(String description, String usage) {
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

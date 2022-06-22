package net.ildar.wurm;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.GroundItemData;
import com.wurmonline.client.renderer.PickData;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.gui.*;
import com.wurmonline.client.util.Computer;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.shared.constants.PlayerAction;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import net.ildar.wurm.bot.BulkItemGetterBot;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.vecmath.Color3f;

public class WurmHelper implements WurmClientMod, Initable, Configurable, PreInitable {
    private final long BLESS_TIMEOUT = 1800000;

    public static HeadsUpDisplay hud;
    private static WurmHelper instance;
    public static boolean hideMount = false;
    public static boolean hideStructures = false;
    public static boolean showTileCoords = false;

    public List<WurmComponent> components;
    private Logger logger;
    private Map<ConsoleCommand, ConsoleCommandHandler> consoleCommandHandlers;
    private long lastBless = 0L;
    private boolean noBlessings = false;
    public static Color3f consoleColor = new Color3f(0.5f, 1, 1);

    public WurmHelper() {
        logger = Logger.getLogger("WurmHelper");
        consoleCommandHandlers = new HashMap<>();
        consoleCommandHandlers.put(ConsoleCommand.sleep, this::handleSleepCommand);
        consoleCommandHandlers.put(ConsoleCommand.look, this::handleLookCommand);
        consoleCommandHandlers.put(ConsoleCommand.combine, input -> handleCombineCommand());
        consoleCommandHandlers.put(ConsoleCommand.move, this::handleMoveCommand);
        consoleCommandHandlers.put(ConsoleCommand.stabilize, input -> Utils.stabilizePlayer());
        consoleCommandHandlers.put(ConsoleCommand.bot, this::handleBotCommand);
        consoleCommandHandlers.put(ConsoleCommand.mts, this::handleMtsCommand);
        consoleCommandHandlers.put(ConsoleCommand.info, this::handleInfoCommand);
        consoleCommandHandlers.put(ConsoleCommand.actionlist, input -> showActionList());
        consoleCommandHandlers.put(ConsoleCommand.action, this::handleActionCommand);
        consoleCommandHandlers.put(ConsoleCommand.getid, input -> copyIdToClipboard());
        consoleCommandHandlers.put(ConsoleCommand.mtcenter, input -> Utils.moveToCenter());
        consoleCommandHandlers.put(ConsoleCommand.mtcorner, input -> Utils.moveToNearestCorner());
        consoleCommandHandlers.put(ConsoleCommand.stabilizelook, input -> Utils.stabilizeLook());
        consoleCommandHandlers.put(ConsoleCommand.hidemount, this::toggleHideMount);
        consoleCommandHandlers.put(ConsoleCommand.hidestructures, this::toggleHideStructures);
        consoleCommandHandlers.put(ConsoleCommand.showcoords, this::toggleShowCoords);
        WurmHelper.instance = this;
    }

    public static WurmHelper getInstance() {
        return instance;
    }

    /**
     * Handle console commands
     */
    @SuppressWarnings("unused")
    public boolean handleInput(final String cmd, final String[] data) {
        ConsoleCommand consoleCommand = ConsoleCommand.getByName(cmd);
        if (consoleCommand == ConsoleCommand.unknown)
            return false;
        ConsoleCommandHandler consoleCommandHandler = consoleCommandHandlers.get(consoleCommand);
        if (consoleCommandHandler == null)
            return false;
        try {
            consoleCommandHandler.handle(Arrays.copyOfRange(data, 1, data.length));
            if (!noBlessings && Math.abs(lastBless - System.currentTimeMillis()) > BLESS_TIMEOUT) {
                hud.addOnscreenMessage("Ildar blesses you!", 1, 1, 1, (byte)1);
                lastBless = System.currentTimeMillis();
            }
        } catch (Exception e) {
            Utils.consolePrint("Error on execution of command \"" + consoleCommand.name() + "\"");
            e.printStackTrace();
        }
        return true;
    }

    private void handleBotCommand(String[] input) {
        BotController.getInstance().handleInput(input);
    }

    private void printConsoleCommandUsage(ConsoleCommand consoleCommand) {
        if (consoleCommand == ConsoleCommand.look) {
            Utils.consolePrint("Usage: " + ConsoleCommand.look.name() + " {" + getCardinalDirectionsList() + "}");
            return;
        }
        if (consoleCommand == ConsoleCommand.bot) {
            Utils.consolePrint(BotController.getInstance().getBotUsageString());
            return;
        }
        Utils.consolePrint("Usage: " + consoleCommand.name() + " " + consoleCommand.getUsage());
    }

    private void copyIdToClipboard() {
        int x = hud.getWorld().getClient().getXMouse();
        int y = hud.getWorld().getClient().getYMouse();
        long[] ids = hud.getCommandTargetsFrom(x, y);
        if (ids != null && ids.length > 0) {
            Computer.setClipboardContents(String.valueOf(ids[0]));
            Utils.showOnScreenMessage("The item id was added to clipboard");
        }
        else {
            PickableUnit pickableUnit = hud.getWorld().getCurrentHoveredObject();
            if (pickableUnit != null) {
                Computer.setClipboardContents(String.valueOf(pickableUnit.getId()));
                Utils.showOnScreenMessage("The item id was added to clipboard");
            } else
                Utils.showOnScreenMessage("Hover the mouse over the item first");
        }
    }

    private void handleInfoCommand(String [] input) {
        if (input.length != 1) {
            printConsoleCommandUsage(ConsoleCommand.info);
            printAvailableConsoleCommands();
            return;
        }

        ConsoleCommand command = ConsoleCommand.getByName(input[0]);
        if (command == ConsoleCommand.unknown) {
            Utils.consolePrint("Unknown console command");
            return;
        }
        printConsoleCommandUsage(command);
        Utils.consolePrint(command.description);
    }

    private void showActionList() {
        for(Action action: Action.values()) {
            Utils.consolePrint("\"" + action.abbreviation + "\" is to " + action.name() + " with tool \"" + action.toolName + "\"");
        }
    }

    private void handleActionCommand(String [] input) {
        if (input == null || input.length == 0) {
            printConsoleCommandUsage(ConsoleCommand.action);
            return;
        }
        StringBuilder abbreviation = new StringBuilder(input[0]);
        for (int i = 1; i < input.length; i++) {
            abbreviation.append(" ").append(input[i]);
        }
        Action action = Action.getByAbbreviation(abbreviation.toString());
        if (action == null) {
            Utils.consolePrint("Unknown action abbreviation - " + abbreviation.toString());
            showActionList();
            return;
        }
        InventoryMetaItem toolItem = Utils.locateToolItem(action.toolName);
        if (toolItem == null && action == Action.Butcher) {
            Utils.consolePrint("A player don't have " + Action.Butcher.toolName + ", trying to find carving knife...");
            toolItem = Utils.locateToolItem("carving knife");
            if (toolItem == null)
                Utils.consolePrint("But the player don't have a carving knife too");
        }
        if (toolItem == null) {
            Utils.consolePrint("A player don't have " + action.toolName);
            return;
        }
        int x = hud.getWorld().getClient().getXMouse();
        int y = hud.getWorld().getClient().getYMouse();
        long[] ids = hud.getCommandTargetsFrom(x, y);
        if (ids != null && ids.length > 0)
            hud.getWorld().getServerConnection().sendAction(toolItem.getId(), new long[]{ids[0]}, action.playerAction);
        else {
            PickableUnit pickableUnit = hud.getWorld().getCurrentHoveredObject();
            if (pickableUnit != null)
                hud.getWorld().getServerConnection().sendAction(toolItem.getId(), new long[]{pickableUnit.getId()}, action.playerAction);
        }
    }

    private void printItemInformation() {
        WurmComponent inventoryComponent = Utils.getTargetComponent(c -> c instanceof ItemListWindow || c instanceof InventoryWindow);
        if (inventoryComponent == null) {
            final PickableUnit unit = hud.getWorld().getCurrentHoveredObject();
            if(unit != null && (unit instanceof GroundItemCellRenderable))
                printGroundItemInfo((GroundItemCellRenderable)unit);
            else
                Utils.consolePrint("Not hovering over any inventory or ground item");
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
        List<InventoryMetaItem> items = Utils.getSelectedItems(ilc);
        if (items == null || items.size() == 0) {
            Utils.consolePrint("No items are selected");
            return;
        }
        for(InventoryMetaItem item : items)
            printItemInfo(item);
    }

    private void printTileInformation() {
        int checkedtiles[][] = Utils.getAreaCoordinates();
        for (int[] checkedtile : checkedtiles) {
            Tiles.Tile tileType = hud.getWorld().getNearTerrainBuffer().getTileType(checkedtile[0], checkedtile[1]);
            Utils.consolePrint("Tile (" + checkedtile[0] + ", " + checkedtile[1] + ") " + tileType.tilename);
        }
    }

    private void printPlayerInformation() {
        Utils.consolePrint("Player \"" + hud.getWorld().getPlayer().getPlayerName() + "\"");
        Utils.consolePrint("Stamina: " + hud.getWorld().getPlayer().getStamina());
        Utils.consolePrint("Damage: " + hud.getWorld().getPlayer().getDamage());
        Utils.consolePrint("Thirst: " + hud.getWorld().getPlayer().getThirst());
        Utils.consolePrint("Hunger: " + hud.getWorld().getPlayer().getHunger());
        Utils.consolePrint("X: " + hud.getWorld().getPlayerPosX() / 4 + " Y: " + hud.getWorld().getPlayerPosY() / 4 + " H: " + hud.getWorld().getPlayerPosH());
        Utils.consolePrint("XRot: " + hud.getWorld().getPlayerRotX() + " YRot: " + hud.getWorld().getPlayerRotY());
        Utils.consolePrint("Layer: " + hud.getWorld().getPlayerLayer());
    }

    private void printCreatureInformation()
    {
        try
        {
            PickableUnit unit = ReflectionUtil.getPrivateField(
                hud.getWorld(),
                ReflectionUtil.getField(
                    hud.getWorld().getClass(),
                    "currentHoveredObject"
                )
            );
            if(!(unit instanceof CreatureCellRenderable))
            {
                Utils.consolePrint("Not hovering over creature");
                return;
            }
            CreatureCellRenderable creature = (CreatureCellRenderable)unit;
            
            Utils.consolePrint("Creature `%s`", creature.getHoverName());
            Utils.consolePrint("    ID: %s", creature.getId());
            Utils.consolePrint("    Pos: %s,%s (height %s)", creature.getXPos(), creature.getYPos(), creature.getHPos());
            Utils.consolePrint("    Distance: %s", creature.getLengthFromPlayer());
            Utils.consolePrint("    Layer: %s", creature.getLayer());
            Utils.consolePrint("    Model: %s", creature.getModelName());
            Utils.consolePrint("    Health: %s", creature.getPercentHealth());
        }
        catch(Exception err)
        {
            Utils.consolePrint(
                "Got %s when trying to print creature info: %s",
                err.getClass().getName(),
                err.getMessage()
            );
        }
    }
    
    private void printItemInfo(InventoryMetaItem item) {
        if (item == null) {
            Utils.consolePrint("Null item");
            return;
        }
        Utils.consolePrint("Item - \"" + item.getBaseName() + " with id " + item.getId());
        Utils.consolePrint(" QL:" + String.format("%.2f", item.getQuality()) + " DMG:" + String.format("%.2f", item.getDamage()) + " Weight:" + item.getWeight());
        Utils.consolePrint(" Rarity:" + item.getRarity() + " Color:" + String.format("(%d,%d,%d)", (int)(item.getR()*255), (int)(item.getG()*255), (int)(item.getB()*255)));
        List<InventoryMetaItem> children = item.getChildren();
        int childCound = children!=null?children.size():0;
        Utils.consolePrint(" Improve icon id:" + item.getImproveIconId() + " Child count:" + childCound + " Material id:" + item.getMaterialId());
        Utils.consolePrint(" Aux data:" + item.getAuxData() + " Price:" + item.getPrice() + " Temperature:" + item.getTemperature() + " " + item.getTemperatureStateText());
        Utils.consolePrint(" Custom name:" + item.getCustomName() + " Group name:" + item.getGroupName() + " Display name:" + item.getDisplayName());
        Utils.consolePrint(" Type:" + item.getType() + " Type bits:" + item.getTypeBits() + " Parent id:" + item.getParentId());
        Utils.consolePrint(" Color override:" + item.isColorOverride() + " Marked for update:" + item.isMarkedForUpdate() + " Unfinished:" + item.isUnfinished());
    }
    
    private void printGroundItemInfo(GroundItemCellRenderable item) {
        GroundItemData data;
        try {
            data = ReflectionUtil.getPrivateField(item, ReflectionUtil.getField(item.getClass(), "item"));
        } catch(Exception err) {
            Utils.consolePrint("Couldn't get GroundItemData for item %s");
            return;
        }
        
        Utils.consolePrint("Ground item \"%s\" (\"%s\") with id %d", data.getName(), data.getHoverText(), item.getId());
        Utils.consolePrint(" Position: %.3f,%.3f (height %.3f) in layer %d", item.getXPos(), item.getYPos(), item.getHPos(), item.getLayer());
        Utils.consolePrint(" Distance from player: %.3fm", item.getLengthFromPlayer());
        Utils.consolePrint(" Color: %d,%d,%d",
            (int)(255 * data.getR()) & 0xFF,
            (int)(255 * data.getG()) & 0xFF,
            (int)(255 * data.getB()) & 0xFF
        );
        Utils.consolePrint(" Model name: %s", data.getModelName());
        Utils.consolePrint(" Description: \"%s\"", data.getDescription());
    }

    private void handleMtsCommand(String []input) {
        if (input.length < 2)  {
            printConsoleCommandUsage(ConsoleCommand.mts);
            return;
        }

        float coefficient = 1;
        if (input.length == 3) {
            try {
                coefficient = Float.parseFloat(input[2]);
            } catch (NumberFormatException e) {
                Utils.consolePrint("Wrong coefficient value. Should be float");
                return;
            }
        }
        float favorLevel;
        try {
            favorLevel = Float.parseFloat(input[1]);
        } catch(NumberFormatException e) {
            Utils.consolePrint("Invalid float level number. Should be float");
            return;
        }
        moveToSacrifice(input[0], favorLevel, coefficient);
    }

    private void handleMoveCommand(String []input) {
        if (input.length == 1) {
            try {
                float d = Float.parseFloat(input[0]);
                Utils.movePlayer(d);
            } catch (NumberFormatException e) {
                printConsoleCommandUsage(ConsoleCommand.move);
            }
        }
        else
            printConsoleCommandUsage(ConsoleCommand.move);
    }

    private void handleCombineCommand() {
        long[] itemsToCombine = hud.getInventoryWindow().getInventoryListComponent().getSelectedCommandTargets();
        if (itemsToCombine == null || itemsToCombine.length == 0) {
            Utils.consolePrint("No selected items!");
            return;
        }
        hud.getWorld().getServerConnection().sendAction(itemsToCombine[0], itemsToCombine, PlayerAction.COMBINE);
    }

    private void handleSleepCommand(String [] input) {
        if (input.length == 1) {
            try {
                Thread.sleep(Long.parseLong(input[0]));
            }catch(InputMismatchException e) {
                Utils.consolePrint("Bad value");
            }catch(InterruptedException e) {
                Utils.consolePrint("Interrupted");
            }
        } else printConsoleCommandUsage(ConsoleCommand.sleep);
    }

    private void handleLookCommand(String []input) {
        if (input.length == 1) {
            CardinalDirection direction = CardinalDirection.getByName(input[0]);
            if (direction == CardinalDirection.unknown) {
                Utils.consolePrint("Unknown direction: " + input[0]);
                return;
            }
            try {
                Utils.turnPlayer(direction.angle,0);
            } catch (Exception e) {
                Utils.consolePrint("Can't change looking direction");
            }
        } else
            printConsoleCommandUsage(ConsoleCommand.look);
    }

    private String getCardinalDirectionsList() {
        StringBuilder directions = new StringBuilder();
        for(CardinalDirection direction : CardinalDirection.values())
            directions.append(direction.name()).append("|");
        directions.deleteCharAt(directions.length() - 1);
        return directions.toString();
    }

    private void printAvailableConsoleCommands() {
        StringBuilder commands = new StringBuilder();
        for(ConsoleCommand consoleCommand : consoleCommandHandlers.keySet())
            commands.append(consoleCommand.name()).append(", ");
        commands.deleteCharAt(commands.length() - 1);
        commands.deleteCharAt(commands.length() - 1);
        Utils.consolePrint("Available custom commands - " + commands.toString());
    }

    //move items to altar for a sacrifice
    private void moveToSacrifice(String itemName, float favorLevel, float coefficient) {
        WurmComponent inventoryComponent = Utils.getTargetComponent(c -> c instanceof ItemListWindow || c instanceof InventoryWindow);
        if (inventoryComponent == null) {
            Utils.consolePrint("Didn't find an inventory under the mouse cursor");
            return;
        }
        InventoryListComponent ilc;
        try {
            ilc = ReflectionUtil.getPrivateField(inventoryComponent,
                    ReflectionUtil.getField(inventoryComponent.getClass(), "component"));
        } catch(Exception e) {
            e.printStackTrace();
            return;
        }
        List<InventoryMetaItem> items = Utils.getInventoryItems(ilc, itemName);
        if (items == null || items.size() == 0) {
            Utils.consolePrint("No items");
            return;
        }
        List<InventoryMetaItem> itemsToMove = new ArrayList<>();
        float favor = WurmHelper.hud.getWorld().getPlayer().getSkillSet().getSkillValue("favor");
        for (InventoryMetaItem item : items) {
            if (favor >= favorLevel) break;
            itemsToMove.add(item);
            favor += Utils.itemFavor(item, coefficient);
        }
        if (itemsToMove.size() == 0){
            Utils.consolePrint("No items to move");
            return;
        }
        if (components == null) {
            Utils.consolePrint("Components list is empty!");
            return;
        }
        for(WurmComponent component : components) {
            if (component instanceof ItemListWindow){
                try {
                    ilc = ReflectionUtil.getPrivateField(component,
                            ReflectionUtil.getField(component.getClass(), "component"));
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
                InventoryMetaItem rootItem = Utils.getRootItem(ilc);
                if (rootItem == null) {
                    Utils.consolePrint("Internal error on moving items");
                    return;
                }
                if (!rootItem.getBaseName().contains("altar of")) continue;
                if (rootItem.getChildren() != null && rootItem.getChildren().size() > 0) {
                    Utils.showOnScreenMessage("An altar is not empty!");
                    return;
                }
                WurmHelper.hud.getWorld().getServerConnection().sendMoveSomeItems(rootItem.getId(), Utils.getItemIds(itemsToMove));
                WurmHelper.hud.sendAction(PlayerAction.SACRIFICE, rootItem.getId());
                return;
            }
        }
        Utils.consolePrint("Didn't find an opened altar");
    }
    
    private void toggleHideMount(String[] args) {
        hideMount = !hideMount;
        Utils.consolePrint(
            "Mount is now %s",
            hideMount ? "hidden" : "visible"
        );
    }
    
    private void toggleHideStructures(String[] args) {
        hideStructures = !hideStructures;
        Utils.consolePrint(
            "Structures are now %s",
            hideStructures ? "hidden" : "visible"
        );
    }
    
    private void toggleShowCoords(String[] args) {
        showTileCoords = !showTileCoords;
        Utils.consolePrint(
            "Tile coordinates are now %s",
            showTileCoords ? "visible" : "hidden"
        );
    }
    
    public static void addCoordsText(int x, int y, int section, final PickData pickData) {
        String prefix;
        switch(section) {
            case 1:
                prefix = "North border of ";
                break;
            case 2:
                prefix = "West border of ";
                break;
            // cave walls
            case -1:
                prefix = "Floor of ";
                break;
            case -2:
                prefix = "Ceiling of ";
                break;
            case -3:
                prefix = "East face of ";
                break;
            case -4:
                prefix = "South face of ";
                break;
            case -5:
                prefix = "West face of ";
                break;
            case -6:
                prefix = "North face of ";
                break;
            // TODO: cave tile borders? their coords and `wallSide` are really funky though
            default:
                prefix = "";
        }
        pickData.addText(String.format("%s%d, %d", prefix, x, y));
    }

    @Override
    public void configure(Properties properties) {
        String enableInfoCommands = properties.getProperty("DevInfoCommands", "false");
        if (enableInfoCommands.equalsIgnoreCase("true")) {
            consoleCommandHandlers.put(ConsoleCommand.iteminfo, input -> printItemInformation());
            consoleCommandHandlers.put(ConsoleCommand.tileinfo, input -> printTileInformation());
            consoleCommandHandlers.put(ConsoleCommand.playerinfo, input -> printPlayerInformation());
            consoleCommandHandlers.put(ConsoleCommand.creatureinfo, input -> printCreatureInformation());
        }
        
        String noBlessings = properties.getProperty("NoBlessings", "false");
        this.noBlessings = noBlessings.equalsIgnoreCase("true");
        
        String consoleMsgColor = properties.getProperty("ConsoleMsgColor", "0.5,1.0,1.0");
        try {
            String[] bits = consoleMsgColor.split(",");
            float r = Float.parseFloat(bits[0]);
            float g = Float.parseFloat(bits[1]);
            float b = Float.parseFloat(bits[2]);
            consoleColor = new Color3f(r, g, b);
        } catch(Exception err) {
            Utils.consolePrint(
                "%s: failed to parse ConsoleMsgColor property, using default",
                WurmHelper.class.getSimpleName()
            );
        }
    }

    @Override
    public void preInit() {
        try {
            final ClassPool classPool = HookManager.getInstance().getClassPool();
            final CtClass ctWurmConsole = classPool.getCtClass("com.wurmonline.client.console.WurmConsole");
            ctWurmConsole.getMethod("handleDevInput", "(Ljava/lang/String;[Ljava/lang/String;)Z").insertBefore("if (net.ildar.wurm.WurmHelper.getInstance().handleInput($1,$2)) return true;");

            final CtClass ctSocketConnection = classPool.getCtClass("com.wurmonline.communication.SocketConnection");
            ctSocketConnection.getMethod("tickWriting", "(J)Z").insertBefore("net.ildar.wurm.Utils.serverCallLock.lock();");
            ctSocketConnection.getMethod("tickWriting", "(J)Z").insertAfter("net.ildar.wurm.Utils.serverCallLock.unlock();");
            ctSocketConnection.getMethod("getBuffer", "()Ljava/nio/ByteBuffer;").insertBefore("net.ildar.wurm.Utils.serverCallLock.lock();");
            ctSocketConnection.getMethod("flush", "()V").insertAfter("net.ildar.wurm.Utils.serverCallLock.unlock();");

            final CtClass ctConsoleComponent = classPool.getCtClass("com.wurmonline.client.renderer.gui.ConsoleComponent");
            CtMethod consoleGameTickMethod = CtNewMethod.make(
                "public void gameTick() {" +
                "  javax.vecmath.Color3f c = net.ildar.wurm.WurmHelper.consoleColor;" +
                "  while(!net.ildar.wurm.Utils.consoleMessages.isEmpty()) {" +
                "    addLine((String)net.ildar.wurm.Utils.consoleMessages.poll(), c.x, c.y, c.z);" +
                "  }" +
                "  super.gameTick();" +
                "};",
                ctConsoleComponent
            );
            ctConsoleComponent.addMethod(consoleGameTickMethod);

            final CtClass ctWurmChat = classPool.getCtClass("com.wurmonline.client.renderer.gui.ChatPanelComponent");
            ctWurmChat.getMethod("addText", "(Ljava/lang/String;Ljava/util/List;Z)V").insertBefore("net.ildar.wurm.Chat.onMessage($1,$2,$3);");
            ctWurmChat.getMethod("addText", "(Ljava/lang/String;Ljava/lang/String;FFFZ)V").insertBefore("net.ildar.wurm.Chat.onMessage($1,$2,$6);");

            CtClass itemCellRenderableClass = classPool.getCtClass("com.wurmonline.client.renderer.cell.GroundItemCellRenderable");
            itemCellRenderableClass.defrost();
            CtMethod itemCellRenderableInitializeMethod = CtNewMethod.make("public void initialize() {\n" +
                    "                if (net.ildar.wurm.BotController.getInstance().isInstantiated(net.ildar.wurm.bot.GroundItemGetterBot.class)) {\n" +
                    "                   net.ildar.wurm.bot.Bot gigBot = net.ildar.wurm.BotController.getInstance().getInstance(net.ildar.wurm.bot.GroundItemGetterBot.class);" +
                    "                   ((net.ildar.wurm.bot.GroundItemGetterBot)gigBot).processNewItem(this);\n" +
                    "                }\n" +
                    "        super.initialize();\n" +
                    "    };", itemCellRenderableClass);
            itemCellRenderableClass.addMethod(itemCellRenderableInitializeMethod);
            
            CtClass structureDataClass = classPool.getCtClass("com.wurmonline.client.renderer.structures.StructureData");
            structureDataClass.getMethod("isVisible", "(Lcom/wurmonline/client/renderer/Frustum;)Z").insertBefore(
                "if(net.ildar.wurm.WurmHelper.hideStructures) return false;"
            );
            CtClass meshClass = classPool.getCtClass("com.wurmonline.client.renderer.mesh.Mesh");
            meshClass.getMethod("isVisible", "(Lcom/wurmonline/client/renderer/Frustum;)Z").insertBefore(
                "if(net.ildar.wurm.WurmHelper.hideStructures) return false;"
            );
            
            CtClass creatureRenderable = classPool.getCtClass("com.wurmonline.client.renderer.cell.CreatureCellRenderable");
            creatureRenderable.getMethod("isVisible", "(Lcom/wurmonline/client/renderer/Frustum;)Z").insertBefore(
                "if(net.ildar.wurm.WurmHelper.hideMount && " +
                "this == net.ildar.wurm.WurmHelper.hud.getWorld().getPlayer().getCarrierCreature())" +
                "return false;"
            );
            
            CtClass tilePicker = classPool.getCtClass("com.wurmonline.client.renderer.TilePicker");
            tilePicker.getMethod("getHoverDescription", "(Lcom/wurmonline/client/renderer/PickData;)V").insertAfter(
                "if(net.ildar.wurm.WurmHelper.showTileCoords)" +
                "  net.ildar.wurm.WurmHelper.addCoordsText(x, y, section, $1);"
            );
            CtClass cavePicker = classPool.getCtClass("com.wurmonline.client.renderer.cave.CaveWallPicker");
            cavePicker.getMethod("getHoverDescription", "(Lcom/wurmonline/client/renderer/PickData;)V").insertAfter(
                "final int tilex = (this.wallSide == 4) ? (this.x + 1) : ((this.wallSide == 2) ? (this.x - 1) : this.x);" +
                "final int tiley = (this.wallSide == 5) ? (this.y + 1) : ((this.wallSide == 3) ? (this.y - 1) : this.y);" +
                "if(net.ildar.wurm.WurmHelper.showTileCoords)" +
                "  net.ildar.wurm.WurmHelper.addCoordsText(tilex, tiley, -1 - wallSide, $1);"
            );
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error loading mod", e);
            logger.log(Level.SEVERE, e.toString());
            throw new RuntimeException(e);
        }
    }

    public void init() {
        try {
            HookManager.getInstance().registerHook("com.wurmonline.client.renderer.gui.HeadsUpDisplay", "init", "(II)V", () -> (proxy, method, args) -> {
                method.invoke(proxy, args);
                WurmHelper.hud = (HeadsUpDisplay)proxy;
                return null;
            });

            HookManager.getInstance().registerHook("com.wurmonline.client.renderer.gui.HeadsUpDisplay", "addComponent", "(Lcom/wurmonline/client/renderer/gui/WurmComponent;)Z", () -> (proxy, method, args) -> {
                WurmComponent wc = (WurmComponent)args[0];
                boolean notadd = false;
                if (BulkItemGetterBot.closeBMLWindow && wc instanceof BmlWindowComponent) {
                    String title = ReflectionUtil.getPrivateField(wc, ReflectionUtil.getField(wc.getClass(), "title"));
                    if (title.equals("Removing items")) {
                        if(BulkItemGetterBot.moveQuantity > 0)
                        {
                            Map<String, Object> inputs = ReflectionUtil.getPrivateField(
                                wc,
                                ReflectionUtil.getField(wc.getClass(), "inputFields")
                            );
                            Object quantityField = inputs.values().iterator().next();
                            ReflectionUtil.setPrivateField(
                                quantityField,
                                ReflectionUtil.getField(quantityField.getClass(), "input"),
                                String.format("%d", BulkItemGetterBot.moveQuantity)
                            );
                        }
                        
                        Method clickButton = ReflectionUtil.getMethod(wc.getClass(), "processButtonPressed");
                        clickButton.setAccessible(true);
                        clickButton.invoke(wc, "submit");
                        notadd = true;
                        BulkItemGetterBot.closeBMLWindow = false;
                    }
                }
                if (!notadd) {
                    Object o = method.invoke(proxy, args);
                    components = new ArrayList<>(ReflectionUtil.getPrivateField(proxy, ReflectionUtil.getField(proxy.getClass(), "components")));
                    return o;
                }
                return (Object)true;
            });
            HookManager.getInstance().registerHook("com.wurmonline.client.renderer.gui.HeadsUpDisplay", "setActiveWindow", "(Lcom/wurmonline/client/renderer/gui/WurmComponent;)V", () -> (proxy, method, args) -> {
                method.invoke(proxy, args);
                components = new ArrayList<>(ReflectionUtil.getPrivateField(proxy, ReflectionUtil.getField(proxy.getClass(), "components")));
                return null;
            });

            Chat.registerMessageProcessor(":Event", message -> message.contains("You fail to relax"), () -> {
                try {
                    PickableUnit pickableUnit = ReflectionUtil.getPrivateField(WurmHelper.hud.getSelectBar(),
                            ReflectionUtil.getField(WurmHelper.hud.getSelectBar().getClass(), "selectedUnit"));
                    if (pickableUnit != null)
                        WurmHelper.hud.sendAction(new PlayerAction("",(short) 384, PlayerAction.ANYTHING), pickableUnit.getId());
                } catch (Exception e) {
                    Utils.consolePrint("Got exception at the start of meditation " + e.getMessage());
                    Utils.consolePrint(e.toString());
                }
            });

            logger.info("Loaded");
        }
        catch (Exception e) {
            logger.log(Level.SEVERE, "Error loading mod", e);
            logger.log(Level.SEVERE, e.toString());
        }
    }

    public enum ConsoleCommand{
        unknown("", ""),
        sleep("timeout(milliseconds)", "Freezes the game for specified time."),
        look("{north|east|south|west}", "Precisely turns player in chosen direction."),
        combine("", "Combines selected items in your inventory."),
        move("(float)distance", "Moves your character in current direction for specified distance(in meters, 1 tile has 4 meters on each side)."),
        stabilize("", "Moves your character to the very center of the tile + turns the sight towards nearest cardinal direction."),
        mtcenter("", "Moves your character to the center of the tile"),
        mtcorner("", "Moves your character to the nearest tile corner"),
        stabilizelook("", "Turns the sight towards nearest cardinal direction"),
        bot("abbreviation", "Activates/configures the bot with provided abbreviation."),
        mts("item_name favor_level [coefficient]",
                "Move specified items to opened altar inventory. " +
                "The amount of moved items depends on specified favor(with coefficient) you want to get from these items when you sacrifice them."),
        info("command", "Shows the description of specified console command."),
        iteminfo("", "Prints information about selected items under mouse cursor."),
        tileinfo("", "Prints information about tiles around player"),
        playerinfo("", "Prints some information about the player"),
        creatureinfo("", "Prints information about the hovered creature"),
        actionlist("", "Show the list of available actions to use with \"action\" key"),
        action("abbreviation", "Use the appropritate tool from player's inventory with provided action abbreviation on the hovered object. " +
                "See the list of available actions with \"" + actionlist.name() + "\" command"),
        getid("", "Copy the id of hovered object to the clipboard"),
        hidemount("", "Toggle hiding of mounted creature/vehicle, for easier terraforming (especially underwater)"),
        hidestructures("", "Toggle hiding of structures such as walls, fences, and bridges (for getting misrotated torches back out of stone walls)"),
        showcoords("", "Toggle display of coordinates in tile/border/corner tooltips");

        private String usage;
        public String description;

        ConsoleCommand(String usage, String description) {
            this.usage = usage;
            this.description = description;
        }

        String getUsage() {
            return usage;
        }

        static ConsoleCommand getByName(String name) {
            try {
                return Enum.valueOf(ConsoleCommand.class, name);
            } catch(Exception e) {
                return ConsoleCommand.unknown;
            }
        }
    }

    interface ConsoleCommandHandler {
        void handle(String []input);
    }

    @SuppressWarnings("unused")
    private enum CardinalDirection {
        unknown(0),
        north(0),
        n(0),
        
        northeast(45),
        ne(45),
        
        east(90),
        e(90),
        
        southeast(135),
        se(135),
        
        south(180),
        s(180),
        
        southwest(225),
        sw(225),
        
        west(270),
        w(270),
        
        northwest(315),
        nw(315);

        int angle;
        CardinalDirection(int angle) {
            this.angle = angle;
        }

        static CardinalDirection getByName(String name) {
            try {
                return Enum.valueOf(CardinalDirection.class, name);
            } catch(Exception e) {
                return CardinalDirection.unknown;
            }
        }
    }

    @SuppressWarnings("unused")
    private enum Action{
        Butcher("bu", "butchering knife", PlayerAction.BUTCHER),
        Bury("br", "shovel", PlayerAction.BURY),
        BuryInsideMine("brm", "pickaxe", PlayerAction.BURY),
        CutTree("ct", "hatchet", PlayerAction.CUT_DOWN),
        ChopLog("cl", "hatchet", PlayerAction.CHOP_UP),
        Mine("m", "pickaxe", PlayerAction.MINE_FORWARD),
        TendField("ft", "rake", PlayerAction.FARM),
        Dig("d", "shovel", PlayerAction.DIG),
        DigToPile("dp", "shovel", PlayerAction.DIG_TO_PILE),
        Lockpick("l", "lock picks", new PlayerAction("",(short) 101, PlayerAction.ANYTHING)),
        LightFire("lf", "steel and flint", new PlayerAction("",(short) 12, PlayerAction.ANYTHING)),
        LeadAnimal("la", "rope", PlayerAction.LEAD),
        Sow("s", "seeds", PlayerAction.SOW);

        String abbreviation;
        String toolName;
        PlayerAction playerAction;

        Action(String abbreviation, String toolName, PlayerAction playerAction) {
            this.abbreviation = abbreviation;
            this.toolName = toolName;
            this.playerAction = playerAction;
        }

        static Action getByAbbreviation(String abbreviation) {
            for(Action action : values())
                if(action.abbreviation.equals(abbreviation))
                    return action;
            return null;
        }
    }
}

package net.ildar.wurm.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.gui.InventoryListComponent;
import com.wurmonline.client.renderer.gui.InventoryWindow;
import com.wurmonline.client.renderer.gui.ItemListWindow;
import com.wurmonline.client.renderer.gui.WurmComponent;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;

@BotInfo(abbreviation = "mim", description = "Moves many sets of items to their own containers")
public class MultiItemMoverBot extends Bot
{
    boolean toplevelOnly = true;
    ArrayList<ItemSet> itemSets = new ArrayList<>();
    int selectedSet = 0;
    
    public MultiItemMoverBot()
    {
        itemSets.add(new ItemSet());
        
        registerInputHandler(Inputs.fl, input -> toggleToplevelOnly());
        registerInputHandler(Inputs.ns, input -> newSet());
        registerInputHandler(Inputs.ds, input -> deleteSet());
        registerInputHandler(Inputs.ss, this::selectSet);
        registerInputHandler(Inputs.ls, input -> listSets());
        registerInputHandler(Inputs.st, input -> setTarget(false));
        registerInputHandler(Inputs.str, input -> setTarget(true));
        registerInputHandler(Inputs.a, this::addItem);
    }

    @Override
    public void work() throws Exception
    {
        setTimeout(5000);
        
        while(isActive())
        {
            waitOnPause();
            while(itemSets.size() == 0)
                sleep(1000);
            
            List<InventoryMetaItem> inventoryItems;
            for(ItemSet set: itemSets)
            {
                if(!set.haveTarget() || set.itemNames.size() == 0)
                    continue;
                
                // refresh as items may have been matched by earlier sets
                inventoryItems = getInventoryItems();
                
                List<InventoryMetaItem> toMove = new ArrayList<>();
                for(InventoryMetaItem item: inventoryItems)
                {
                    if(item.getRarity() != 0) continue;
                    
                    for(String matchName: set.itemNames)
                        if(item.getBaseName().contains(matchName))
                            toMove.add(item);
                }
                
                set.moveItems(toMove);
            }
            
            sleep(timeout);
        }
    }
    
    List<InventoryMetaItem> getInventoryItems()
    {
        if(toplevelOnly)
            return Utils.getFirstLevelItems();
        else
            return Utils.getSelectedItems(WurmHelper.hud.getInventoryWindow().getInventoryListComponent(), true, true);
    }
    
    void toggleToplevelOnly()
    {
        toplevelOnly = !toplevelOnly;
        Utils.consolePrint(String.format(
            "Bot will move %s items",
            toplevelOnly ? "only top level" : "all"
        ));
    }
    
    void newSet()
    {
        itemSets.add(new ItemSet());
        selectedSet = itemSets.size() - 1;
        Utils.consolePrint("Created and selected new item set with index %d", selectedSet);
    }
    
    void deleteSet()
    {
        if(itemSets.size() == 0 || selectedSet == -1)
        {
            Utils.consolePrint("Don't have any item sets (or somehow none selected) to delete!");
            return;
        }
        
        itemSets.remove(selectedSet);
        Utils.consolePrint("Deleted item set %d", selectedSet);
        selectedSet = itemSets.size() - 1;
    }
    
    void selectSet(String[] args)
    {
        if(args == null || args.length != 1)
        {
            printInputKeyUsageString(Inputs.cs);
            return;
        }
        
        final int numSets = itemSets.size();
        if(numSets == 0)
        {
            Utils.consolePrint("No item sets to select! Use ns subcommand to create one");
            return;
        }
        
        try
        {
            int newSelection = Integer.parseInt(args[0]);
            if(newSelection >= numSets)
            {
                Utils.consolePrint(
                    "Only have %d item sets, index must be in 0 .. %d",
                    numSets, numSets - 1
                );
                return;
            }
            
            selectedSet = newSelection;
            Utils.consolePrint("Selected item set %d", selectedSet);
        }
        catch(NumberFormatException err)
        {
            Utils.consolePrint("`%s` is not an integer", args[0]);
        }
    }
    
    void listSets()
    {
        if(itemSets.size() == 0)
        {
            Utils.consolePrint("No item sets yet");
            return;
        }
        
        for(int index = 0; index < itemSets.size(); index++)
            Utils.consolePrint(
                "%s %d: %s",
                index == selectedSet ? "*" : " ",
                index,
                String.join(", ", itemSets.get(index).itemNames)
            );
    }
    
    void setTarget(boolean isRoot)
    {
        if(itemSets.size() == 0 || selectedSet == -1)
        {
            Utils.consolePrint("Don't have any item sets (or somehow none selected)");
            return;
        }
        itemSets.get(selectedSet).setTarget(isRoot);
    }
    
    void addItem(String[] args)
    {
        if(args.length == 0)
        {
            printInputKeyUsageString(Inputs.a);
            return;
        }
        
        if(itemSets.size() == 0 || selectedSet == -1)
        {
            Utils.consolePrint("Don't have any item sets (or somehow none selected)");
            return;
        }
        
        String[] itemNames = String.join(" ", args).split("\\s*,\\s*");
        ItemSet selected = itemSets.get(selectedSet);
        for(String name: itemNames)
            selected.itemNames.add(name);
        Utils.consolePrint(
            "Items for set %d now: %s",
            selectedSet,
            String.join(", ", selected.itemNames)
        );
    }
    
    void clearItems()
    {
        if(itemSets.size() == 0 || selectedSet == -1)
        {
            Utils.consolePrint("Don't have any item sets (or somehow none selected)");
            return;
        }
        
        itemSets.get(selectedSet).itemNames.clear();
        Utils.consolePrint("Cleared items for set %d", selectedSet);
    }
    
    static class ItemSet
    {
        HashSet<String> itemNames = new HashSet<>();
        boolean isRootTarget;
        long target;
        InventoryListComponent targetComponent;
        InventoryMetaItem targetRoot;
        
        boolean haveTarget()
        {
            return target > 0 || isRootTarget && targetComponent != null && targetRoot != null;
        }
        
        void setTarget(boolean isRoot)
        {
            isRootTarget = isRoot;
            target = -1;
            targetComponent = null;
            targetRoot = null;
            if(isRoot)
            {
                WurmComponent inventoryComponent = Utils.getTargetComponent(c -> c instanceof ItemListWindow || c instanceof InventoryWindow);
                if(inventoryComponent == null)
                {
                    Utils.consolePrint("Couldn't find an open container");
                    return;
                }
                
                InventoryListComponent listComponent;
                try
                {
                    listComponent = ReflectionUtil.getPrivateField(
                        inventoryComponent,
                        ReflectionUtil.getField(inventoryComponent.getClass(), "component")
                    );
                }
                catch(Exception err)
                {
                    Utils.consolePrint("Couldn't get container's ListComponent");
                    err.printStackTrace();
                    return;
                }
                
                InventoryMetaItem root = Utils.getRootItem(listComponent);
                if(root == null)
                {
                    Utils.consolePrint("ListComponent has no root item?");
                    return;
                }
                
                targetComponent = listComponent;
                targetRoot = root;
                Utils.consolePrint("New target is \"%s\"", root.getBaseName());
            }
            else
            {
                int x = WurmHelper.hud.getWorld().getClient().getXMouse();
                int y = WurmHelper.hud.getWorld().getClient().getXMouse();
                long[] targets = WurmHelper.hud.getCommandTargetsFrom(x, y);
                
                if(targets != null && targets.length > 0)
                {
                    target = targets[0];
                    Utils.consolePrint("New target is %d", target);
                }
                else
                    Utils.consolePrint("Couldn't find item to target");
            }
        }
        
        void moveItems(List<InventoryMetaItem> items)
        {
            if(items.size() == 0) return;
            
            long dest = target;
            if(isRootTarget)
                dest = targetRoot.getId();
            
            long[] ids = Utils.getItemIds(items);
            WurmHelper.hud.getWorld().getServerConnection().sendMoveSomeItems(dest, ids);
                
        }
    }
    
    private enum Inputs implements Bot.InputKey
    {
        fl("Toggle moving of only top-level items", ""),
        
        ns("Create new item set", ""),
        ds("Delete current item set", ""),
        ss("Select item set", "index"),
        ls("Show item sets", ""),
        
        st("Set target item", ""),
        str("Set target container", ""),
        a("Add item to selected set", "name"),
        clear("Clear list of items in selected set", "");
        
        
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

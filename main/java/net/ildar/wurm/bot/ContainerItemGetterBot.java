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

@BotInfo(description = "Retrieves items from containers", abbreviation = "cig")
public class ContainerItemGetterBot extends Bot
{
	HashSet<String> items = new HashSet<>();
	HashSet<InventoryListComponent> sources = new HashSet<>();
	
	public ContainerItemGetterBot()
	{
		registerInputHandler(Inputs.a, this::addItem);
		registerInputHandler(Inputs.c, input -> clearItems());
		registerInputHandler(Inputs.ss, input -> addSource());
		registerInputHandler(Inputs.cs, input -> clearSources());
	}
	
	@Override
	public void work() throws Exception
	{
		setTimeout(5000);
		
		InventoryListComponent playerInv = WurmHelper.hud.getInventoryWindow().getInventoryListComponent();
		InventoryMetaItem playerInvRoot = Utils.getRootItem(playerInv);
		long playerInvID = playerInvRoot.getId();
		
		List<InventoryMetaItem> srcItems = new ArrayList<>();
		while(isActive())
		{
			waitOnPause();
			
			while(isActive() && sources.size() == 0) sleep(1000);
			if(!isActive()) break;
			
			for(InventoryListComponent src: sources)
			{
				srcItems.clear();
				for(String name: items)
					srcItems.addAll(Utils.getInventoryItems(src, name));
				
				long[] ids = Utils.getItemIds(srcItems);
				WurmHelper.hud.getWorld().getServerConnection().sendMoveSomeItems(playerInvID, ids);
			}
			
			sleep(timeout);
		}
	}
	
	void addItem(String[] args)
	{
		if(args.length == 0)
		{
			printInputKeyUsageString(Inputs.a);
			return;
		}
		
		String[] itemNames = String.join(" ", args).split("\\s*,\\s*");
		for(String name: itemNames)
			items.add(name);
		Utils.consolePrint(
			"Getting: %s",
			String.join(", ", items)
		);
	}
	
	void clearItems()
	{
		items.clear();
		Utils.consolePrint("List of items to get cleared");
	}
	
	void addSource()
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
		
		sources.add(listComponent);
		Utils.consolePrint("New target is \"%s\"", root.getBaseName());
	}
	
	void clearSources()
	{
		sources.clear();
		Utils.consolePrint("List of sources cleared");
	}
	
	enum Inputs implements InputKey
	{
		a("Add item to be pulled", "name"),
		c("Clear list of items to pull", ""),
		ss("Add source container to pull from", ""),
		cs("Clear list of source containers", "");
		
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

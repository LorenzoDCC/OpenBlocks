package openblocks.common.tileentity;

import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidHandler;
import openblocks.OpenBlocks;
import openblocks.client.gui.GuiAutoAnvil;
import openblocks.common.container.ContainerAutoAnvil;
import openblocks.common.tileentity.TileEntityAutoAnvil.AutoSlots;
import openmods.api.IHasGui;
import openmods.api.IValueProvider;
import openmods.api.IValueReceiver;
import openmods.gui.misc.IConfigurableGuiSlots;
import openmods.include.IExtendable;
import openmods.include.IncludeInterface;
import openmods.include.IncludeOverride;
import openmods.inventory.GenericInventory;
import openmods.inventory.IInventoryProvider;
import openmods.liquids.SidedFluidHandler;
import openmods.sync.SyncableDirs;
import openmods.sync.SyncableFlags;
import openmods.sync.SyncableTank;
import openmods.tileentity.SyncedTileEntity;
import openmods.utils.*;
import openmods.utils.bitmap.*;

public class TileEntityAutoAnvil extends SyncedTileEntity implements IHasGui, IInventoryProvider, IExtendable, IConfigurableGuiSlots<AutoSlots> {

	protected static final int TOTAL_COOLDOWN = 40;
	public static final int TANK_CAPACITY = EnchantmentUtils.getLiquidForLevel(45);

	protected int cooldown = 0;

	/**
	 * The 3 slots in the inventory
	 */
	public enum Slots {
		tool,
		modifier,
		output
	}

	/**
	 * The keys of the things that can be auto injected/extracted
	 */
	public enum AutoSlots {
		tool,
		modifier,
		output,
		xp
	}

	/**
	 * The shared/syncable objects
	 */
	private SyncableDirs toolSides;
	private SyncableDirs modifierSides;
	private SyncableDirs outputSides;
	private SyncableDirs xpSides;
	private SyncableTank tank;
	private SyncableFlags automaticSlots;

	private final GenericInventory inventory = registerInventoryCallback(new GenericInventory("autoanvil", true, 3) {
		@Override
		public boolean isItemValidForSlot(int i, ItemStack itemstack) {
			if (i == 0 && (!itemstack.getItem().isItemTool(itemstack) && itemstack.getItem() != Items.enchanted_book)) { return false; }
			if (i == 2) { return false; }
			return super.isItemValidForSlot(i, itemstack);
		}
	});

	@IncludeInterface(ISidedInventory.class)
	private final SidedInventoryAdapter slotSides = new SidedInventoryAdapter(inventory);

	@IncludeInterface
	private final IFluidHandler tankWrapper = new SidedFluidHandler.Drain(xpSides, tank);

	public TileEntityAutoAnvil() {
		slotSides.registerSlot(Slots.tool, toolSides, true, false);
		slotSides.registerSlot(Slots.modifier, modifierSides, true, false);
		slotSides.registerSlot(Slots.output, outputSides, false, true);
	}

	@Override
	protected void createSyncedFields() {
		toolSides = new SyncableDirs();
		modifierSides = new SyncableDirs();
		outputSides = new SyncableDirs();
		xpSides = new SyncableDirs();
		tank = new SyncableTank(TANK_CAPACITY, OpenBlocks.XP_FLUID);
		automaticSlots = SyncableFlags.create(AutoSlots.values().length);
	}

	@Override
	public void updateEntity() {
		super.updateEntity();

		if (!worldObj.isRemote) {
			// if we should auto-drink liquid, do it!
			if (automaticSlots.get(AutoSlots.xp)) {
				tank.fillFromSides(100, worldObj, getPosition(), xpSides.getValue());
			}

			if (shouldAutoOutput() && hasOutput()) {
				InventoryUtils.moveItemsToOneOfSides(this, inventory, Slots.output.ordinal(), 1, outputSides.getValue());
			}

			// if we should auto input the tool and we don't currently have one
			if (shouldAutoInputTool() && !hasTool()) {
				InventoryUtils.moveItemsFromOneOfSides(this, inventory, 1, Slots.tool.ordinal(), toolSides.getValue());
			}

			// if we should auto input the modifier
			if (shouldAutoInputModifier()) {
				InventoryUtils.moveItemsFromOneOfSides(this, inventory, 1, Slots.modifier.ordinal(), modifierSides.getValue());
			}

			if (cooldown-- < 0) {
				repairItem();
				cooldown = TOTAL_COOLDOWN;
			}

			if (tank.isDirty()) sync();
		}
	}

	private void repairItem() {
		final VanillaAnvilLogic helper = new VanillaAnvilLogic(inventory.getStackInSlot(Slots.tool), inventory.getStackInSlot(Slots.modifier));

		final ItemStack output = helper.getOutputStack();
		if (output != null) {
			int levelCost = helper.getLevelCost();
			int xpCost = EnchantmentUtils.getExperienceForLevel(levelCost);
			int liquidXpCost = EnchantmentUtils.XPToLiquidRatio(xpCost);

			FluidStack drained = tank.drain(liquidXpCost, false);

			if (drained != null && drained.amount == liquidXpCost) {
				tank.drain(liquidXpCost, true);
				removeModifiers(helper.getModifierCost());
				inventory.setInventorySlotContents(Slots.tool.ordinal(), null);
				inventory.setInventorySlotContents(Slots.output.ordinal(), output);
				worldObj.playSoundEffect(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, "random.anvil_use", 0.3f, 1f);
			}
		}
	}

	private void removeModifiers(int modifierCost) {
		if (modifierCost == -1) {
			inventory.setInventorySlotContents(Slots.modifier.ordinal(), null);
		} else {
			ItemStack modifierStack = inventory.getStackInSlot(Slots.modifier);
			if (modifierStack != null) {
				modifierStack.stackSize -= modifierCost;
				if (modifierStack.stackSize <= 0) inventory.setInventorySlotContents(Slots.modifier.ordinal(), null);
			}
		}
	}

	@Override
	public boolean canOpenGui(EntityPlayer player) {
		return true;
	}

	@Override
	public Object getServerGui(EntityPlayer player) {
		return new ContainerAutoAnvil(player.inventory, this);
	}

	@Override
	public Object getClientGui(EntityPlayer player) {
		return new GuiAutoAnvil(new ContainerAutoAnvil(player.inventory, this));
	}

	public IValueProvider<FluidStack> getFluidProvider() {
		return tank;
	}

	/**
	 * Returns true if we should auto-pull the modifier
	 * 
	 * @return
	 */
	private boolean shouldAutoInputModifier() {
		return automaticSlots.get(AutoSlots.modifier);
	}

	/**
	 * Should the anvil auto output the resulting item?
	 * 
	 * @return
	 */
	public boolean shouldAutoOutput() {
		return automaticSlots.get(AutoSlots.output);
	}

	/**
	 * Checks if there is a stack in the input slot
	 * 
	 * @return
	 */
	private boolean hasTool() {
		return inventory.getStackInSlot(0) != null;
	}

	/**
	 * Should the anvil auto input the tool into slot 0?
	 * 
	 * @return
	 */
	private boolean shouldAutoInputTool() {
		return automaticSlots.get(AutoSlots.tool);
	}

	/**
	 * Does the anvil have something in slot [2]?
	 * 
	 * @return
	 */
	private boolean hasOutput() {
		return inventory.getStackInSlot(2) != null;
	}

	@IncludeOverride
	public boolean canDrain(ForgeDirection from, Fluid fluid) {
		return false;
	}

	@Override
	public IInventory getInventory() {
		return slotSides;
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);
		inventory.writeToNBT(tag);
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		inventory.readFromNBT(tag);
	}

	private SyncableDirs selectSlotMap(AutoSlots slot) {
		switch (slot) {
			case modifier:
				return modifierSides;
			case output:
				return outputSides;
			case tool:
				return toolSides;
			case xp:
				return xpSides;
			default:
				throw MiscUtils.unhandledEnum(slot);
		}
	}

	@Override
	public IValueProvider<Set<ForgeDirection>> createAllowedDirectionsProvider(AutoSlots slot) {
		return selectSlotMap(slot);
	}

	@Override
	public IWriteableBitMap<ForgeDirection> createAllowedDirectionsReceiver(AutoSlots slot) {
		SyncableDirs dirs = selectSlotMap(slot);
		return BitMapUtils.createRpcAdapter(createRpcProxy(dirs, IRpcDirectionBitMap.class));
	}

	@Override
	public IValueProvider<Boolean> createAutoFlagProvider(AutoSlots slot) {
		return BitMapUtils.singleBitProvider(automaticSlots, slot.ordinal());
	}

	@Override
	public IValueReceiver<Boolean> createAutoSlotReceiver(AutoSlots slot) {
		IRpcIntBitMap bits = createRpcProxy(automaticSlots, IRpcIntBitMap.class);
		return BitMapUtils.singleBitReceiver(bits, slot.ordinal());
	}
}

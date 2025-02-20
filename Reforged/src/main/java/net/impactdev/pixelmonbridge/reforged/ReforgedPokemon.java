package net.impactdev.pixelmonbridge.reforged;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.pixelmonmod.pixelmon.Pixelmon;
import com.pixelmonmod.pixelmon.api.pokemon.EnumInitializeCategory;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.battles.attacks.Attack;
import com.pixelmonmod.pixelmon.battles.status.NoStatus;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.ExtraStats;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.StatsType;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.extraStats.LakeTrioStats;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.extraStats.MeltanStats;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.extraStats.MewStats;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.extraStats.MiniorStats;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.extraStats.ShearableStats;
import com.pixelmonmod.pixelmon.enums.EnumRibbonType;
import com.pixelmonmod.pixelmon.enums.EnumSpecies;
import net.impactdev.pixelmonbridge.ImpactDevPokemon;
import net.impactdev.pixelmonbridge.data.factory.JObject;
import net.impactdev.pixelmonbridge.details.PixelmonSource;
import net.impactdev.pixelmonbridge.details.Query;
import net.impactdev.pixelmonbridge.details.SpecKey;
import net.impactdev.pixelmonbridge.details.SpecKeys;
import net.impactdev.pixelmonbridge.details.components.Ability;
import net.impactdev.pixelmonbridge.details.components.EggInfo;
import net.impactdev.pixelmonbridge.details.components.Level;
import net.impactdev.pixelmonbridge.details.components.Marking;
import net.impactdev.pixelmonbridge.details.components.Moves;
import net.impactdev.pixelmonbridge.details.components.Nature;
import net.impactdev.pixelmonbridge.details.components.Pokerus;
import net.impactdev.pixelmonbridge.details.components.Trainer;
import net.impactdev.pixelmonbridge.details.components.generic.ItemStackWrapper;
import net.impactdev.pixelmonbridge.details.components.generic.JSONWrapper;
import net.impactdev.pixelmonbridge.details.components.generic.NBTWrapper;
import net.impactdev.pixelmonbridge.reforged.writer.ReforgedSpecKeyWriter;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ReforgedPokemon implements ImpactDevPokemon<Pokemon> {

    private final ImmutableList<SpecKey<?>> UNSUPPORTED = ImmutableList.copyOf(Lists.newArrayList(
            SpecKeys.LIGHT_TRIO_WORMHOLES,
            SpecKeys.MELOETTA_ACTIVATIONS,
            SpecKeys.SPECIAL_TEXTURE
    ));

    private final Map<SpecKey<?>, Object> data = Maps.newTreeMap((k1, k2) -> {
        Query q1 = k1.getQuery();
        Query q2 = k2.getQuery();

        while(q1.getParts().get(0).compareTo(q2.getParts().get(0)) == 0) {
            q1 = q1.pop();
            q2 = q2.pop();

            if(q1.getParts().size() == 0 || q2.getParts().size() == 0) {
                return Integer.compare(q1.getParts().size(), q2.getParts().size());
            }
        }

        return q1.getParts().get(0).compareTo(q2.getParts().get(0));
    });

    private transient Pokemon pokemon;

    @Override
    public Pokemon getOrCreate() {
        EnumSpecies species = this.get(SpecKeys.SPECIES).flatMap(EnumSpecies::getFromName).orElseThrow(() -> new RuntimeException("Unknown species..."));
        return this.pokemon == null ? this.pokemon = writeAll(Pixelmon.pokemonFactory.create(species)) : this.pokemon;
    }

    @Override
    public <T> boolean supports(SpecKey<T> key) {
        return !this.UNSUPPORTED.contains(key);
    }

    @Override
    public <T> boolean offer(SpecKey<T> key, T data) {
        if(data == null) {
            return false;
        }

        if(SpecKeys.SPECIES.equals(key)) {
            if(!EnumSpecies.getFromName((String) data).isPresent()) {
                return false;
            }
        }

        if(this.supports(key)) {
            this.data.put(key, data);
        } else {
            this.data.put(SpecKeys.GENERATIONS_DATA, this.get(SpecKeys.GENERATIONS_DATA).orElseGet(JSONWrapper::new).append(key, data));
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(SpecKey<T> key) {
        return Optional.ofNullable((T) this.data.get(key));
    }

    @Override
    public Map<SpecKey<?>, Object> getAllDetails() {
        return this.data;
    }

    public static ReforgedPokemon from(Pokemon pokemon) {
        ReforgedPokemon result = new ReforgedPokemon();
        result.offer(SpecKeys.ID, pokemon.getUUID());
        result.offer(SpecKeys.SPECIES, pokemon.getSpecies().name);
        result.offer(SpecKeys.FORM, pokemon.getForm());
        result.offer(SpecKeys.SHINY, pokemon.isShiny());
        result.offer(SpecKeys.LEVEL, new Level(pokemon.getLevel(), pokemon.getExperience(), pokemon.doesLevel()));
        result.offer(SpecKeys.GENDER, pokemon.getGender().ordinal());
        result.offer(SpecKeys.NATURE, new Nature(pokemon.getBaseNature().name(), Optional.ofNullable(pokemon.getMintNature()).map(Enum::name).orElse(null)));
        result.offer(SpecKeys.ABILITY, new Ability(pokemon.getAbilityName(), pokemon.getAbilitySlot()));
        result.offer(SpecKeys.FRIENDSHIP, pokemon.getFriendship());
        result.offer(SpecKeys.GROWTH, pokemon.getGrowth().index);
        result.offer(SpecKeys.NICKNAME, pokemon.getNickname());
        result.offer(SpecKeys.TEXTURE, pokemon.getCustomTexture());

        Optional.ofNullable(pokemon.getCaughtBall())
                .ifPresent(ball -> {
                    result.offer(SpecKeys.POKEBALL, ball.ordinal());
                });

        // If a pokemon doesn't have an original trainer UUID, they shouldn't have an original trainer last known
        // name. The only reason the case of no UUID, last known name present is due to a Reforged bug. To avoid
        // user querying on this, with the potential to fail to even find a match, this will only add original
        // trainer information IF AND ONLY IF the UUID is present.
        Optional.ofNullable(pokemon.getOriginalTrainerUUID())
                .ifPresent(id -> {
                    result.offer(SpecKeys.TRAINER, new Trainer(id, pokemon.getOriginalTrainer()));
                });

        if(pokemon.isEgg()) {
            result.offer(SpecKeys.EGG_INFO, new EggInfo(pokemon.getEggCycles(), pokemon.getEggSteps()));
        }

        if(pokemon.getPokerus() != null) {
            result.offer(SpecKeys.POKERUS, new Pokerus(
                    PixelmonSource.Reforged,
                    pokemon.getPokerus().type.ordinal() == 0 ? 0 : pokemon.getPokerus().type.ordinal() + 0xA,
                    pokemon.getPokerus().secondsSinceInfection,
                    pokemon.getPokerus().announced)
            );
        }

        Moves moves = new Moves();
        for(Attack attack : pokemon.getMoveset().attacks) {
            if(attack == null) {
                continue;
            }

            moves.append(new Moves.Move(attack.getActualMove().getAttackName(), attack.pp, attack.ppLevel));
        }
        result.offer(SpecKeys.MOVESET, moves);

        try {
            Field flags = Pokemon.class.getDeclaredField("specFlags");
            flags.setAccessible(true);
            @SuppressWarnings("unchecked") List<String> f = ((ArrayList<String>)flags.get(pokemon));
            result.offer(SpecKeys.SPEC_CREATION_FLAGS, f);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        result.offer(SpecKeys.RELEARNABLE_MOVES, pokemon.getRelearnableMoves());
        result.offer(SpecKeys.EXTRA_DATA, result.extraData(pokemon));
        result.offer(SpecKeys.HELD_ITEM, new ItemStackWrapper(pokemon.getHeldItem()));
        if(!pokemon.getStatus().equals(NoStatus.noStatus)) {
            result.offer(SpecKeys.STATUS, pokemon.getStatus().type.ordinal());
        }

        if(pokemon.getSpecies() == EnumSpecies.Mew) {
            result.offer(SpecKeys.MEW_CLONES, convert(MewStats.class, pokemon.getExtraStats()).numCloned);
        } else if(pokemon.getSpecies() == EnumSpecies.Azelf || pokemon.getSpecies() == EnumSpecies.Mesprit || pokemon.getSpecies() == EnumSpecies.Uxie) {
            result.offer(SpecKeys.LAKE_TRIO_ENCHANTS, convert(LakeTrioStats.class, pokemon.getExtraStats()).numEnchanted);
        } else if(pokemon.getSpecies() == EnumSpecies.Meltan) {
            result.offer(SpecKeys.MELTAN_ORES_SMELTED, convert(MeltanStats.class, pokemon.getExtraStats()).oresSmelted);
        } else if(pokemon.getSpecies() == EnumSpecies.Mareep) {
            result.offer(SpecKeys.MAREEP_WOOL_GROWTH, convert(ShearableStats.class, pokemon.getExtraStats()).growthStage);
        } else if(pokemon.getSpecies() == EnumSpecies.Minior) {
            result.offer(SpecKeys.MINIOR_COLOR, convert(MiniorStats.class, pokemon.getExtraStats()).color);
        }

        result.offer(SpecKeys.HP, pokemon.getHealth());
        result.offer(SpecKeys.EV_HP, pokemon.getStats().evs.getStat(StatsType.HP));
        result.offer(SpecKeys.EV_ATK, pokemon.getStats().evs.getStat(StatsType.Attack));
        result.offer(SpecKeys.EV_DEF, pokemon.getStats().evs.getStat(StatsType.Defence));
        result.offer(SpecKeys.EV_SPATK, pokemon.getStats().evs.getStat(StatsType.SpecialAttack));
        result.offer(SpecKeys.EV_SPDEF, pokemon.getStats().evs.getStat(StatsType.SpecialDefence));
        result.offer(SpecKeys.EV_SPEED, pokemon.getStats().evs.getStat(StatsType.Speed));
        result.offer(SpecKeys.IV_HP, pokemon.getStats().ivs.getStat(StatsType.HP));
        result.offer(SpecKeys.IV_ATK, pokemon.getStats().ivs.getStat(StatsType.Attack));
        result.offer(SpecKeys.IV_DEF, pokemon.getStats().ivs.getStat(StatsType.Defence));
        result.offer(SpecKeys.IV_SPATK, pokemon.getStats().ivs.getStat(StatsType.SpecialAttack));
        result.offer(SpecKeys.IV_SPDEF, pokemon.getStats().ivs.getStat(StatsType.SpecialDefence));
        result.offer(SpecKeys.IV_SPEED, pokemon.getStats().ivs.getStat(StatsType.Speed));
        result.offer(SpecKeys.HYPER_HP, pokemon.getStats().ivs.isHyperTrained(StatsType.HP));
        result.offer(SpecKeys.HYPER_ATTACK, pokemon.getStats().ivs.isHyperTrained(StatsType.Attack));
        result.offer(SpecKeys.HYPER_DEFENCE, pokemon.getStats().ivs.isHyperTrained(StatsType.Defence));
        result.offer(SpecKeys.HYPER_SPECIAL_ATTACK, pokemon.getStats().ivs.isHyperTrained(StatsType.SpecialAttack));
        result.offer(SpecKeys.HYPER_SPECIAL_DEFENCE, pokemon.getStats().ivs.isHyperTrained(StatsType.SpecialDefence));
        result.offer(SpecKeys.HYPER_SPEED, pokemon.getStats().ivs.isHyperTrained(StatsType.Speed));
        result.offer(SpecKeys.DYNAMAX_LEVEL, pokemon.getDynamaxLevel());
        result.offer(SpecKeys.CAN_GMAX, pokemon.canGigantamax());
        if(pokemon.getPersistentData().hasKey("FusedPokemon")) {
            result.offer(SpecKeys.EMBEDDED_POKEMON, Lists.newArrayList(result.getEmbeddedPokemon(pokemon)));
        }

        List<Marking> marks = Lists.newArrayList();
        List<Integer> ribbons = Lists.newArrayList();
        for(EnumRibbonType entry : pokemon.getRibbons()) {
            Marking.createFor(PixelmonSource.Reforged, entry.ordinal())
                    .map(marks::add)
                    .orElseGet(() -> {
                        ribbons.add(entry.ordinal());
                        return true;
                    });
        }
        result.offer(SpecKeys.MARKS, marks);
        result.offer(SpecKeys.RIBBONS, ribbons);

        NBTTagCompound nbt = new NBTTagCompound();
        pokemon.writeToNBT(nbt);
        nbt = nbt.getCompoundTag("ForgeData");

        if(nbt.hasKey("bridge-api")) {
            NBTTagCompound data = nbt.getCompoundTag("bridge-api");
            if(data.hasKey("generations")) {
                JSONWrapper wrapper = new JSONWrapper();
                String stored = data.getString("generations");
                JsonObject json = new GsonBuilder().create().fromJson(stored, JsonObject.class);
                wrapper = wrapper.deserialize(json);
                result.offer(SpecKeys.GENERATIONS_DATA, wrapper);
            }
        }

        result.pokemon = pokemon;
        return result;
    }

    private Pokemon writeAll(Pokemon pokemon) {
        List<Map.Entry<SpecKey<?>, Object>> prioritized = this.data.entrySet().stream()
                .sorted(Comparator.<Map.Entry<SpecKey<?>, Object>, Integer>comparing(x -> x.getKey().getPriority()).reversed())
                .collect(Collectors.toList());

        boolean initialized = false;
        for(Map.Entry<SpecKey<?>, Object> entry : prioritized) {
            SpecKey<?> key = entry.getKey();
            Object value = entry.getValue();

            if(key.getPriority() <= 0 && !initialized) {
                pokemon.initialize(EnumInitializeCategory.SPECIES);
                initialized = true;
            }

            ReforgedSpecKeyWriter.write(key, pokemon, value);
        }

        return pokemon;
    }

    private static <T extends ExtraStats> T convert(Class<T> type, ExtraStats stats) {
        if(type.isAssignableFrom(stats.getClass())) {
            return type.cast(stats);
        }

        throw new IllegalArgumentException("Invalid stats type");
    }

    public void offerUnsafe(SpecKey<?> key, Object value) {
        if(value == null) {
            return;
        }

        if(this.supports(key)) {
            this.data.put(key, value);
        } else {
            this.data.put(SpecKeys.GENERATIONS_DATA, this.get(SpecKeys.GENERATIONS_DATA).orElseGet(JSONWrapper::new).offerUnsafe(key, value));
        }
    }

    private NBTWrapper extraData(Pokemon pokemon) {
        NBTTagCompound nbt = pokemon.getPersistentData().copy();
        nbt.removeTag("FusedPokemon");
        return new NBTWrapper(nbt);
    }

    private ReforgedPokemon getEmbeddedPokemon(Pokemon pokemon) {
        NBTTagCompound nbt = pokemon.getPersistentData().getCompoundTag("FusedPokemon");
        return ReforgedPokemon.from(Pixelmon.pokemonFactory.create(nbt));
    }

    @Override
    public JObject serialize() {
        ReforgedDataManager manager = new ReforgedDataManager();
        return manager.serialize(this);
    }
}

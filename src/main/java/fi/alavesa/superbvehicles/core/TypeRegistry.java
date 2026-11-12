package fi.alavesa.superbvehicles.core;

import fi.alavesa.superbvehicles.bbmodel.Rig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** All vehicle types (vehicles/*.yml + API-registered) and the rigs of their models (from the last pack build). */
public final class TypeRegistry {
    private final Map<String, VehicleType> types = new LinkedHashMap<>();
    private final Map<String, VehicleType> apiTypes = new LinkedHashMap<>();
    private volatile Map<String, Rig> rigs = new LinkedHashMap<>();
    public final List<String> errors = new ArrayList<>();

    public void load(File vehiclesDir) {
        errors.clear();
        types.clear();
        for (VehicleType t : VehicleType.loadAll(vehiclesDir, errors)) { if (types.containsKey(t.id)) errors.add(t.source.getName() + ": duplicate vehicle id '" + t.id + "'"); else types.put(t.id, t); }
        types.putAll(apiTypes);
    }

    public void register(VehicleType t) { apiTypes.put(t.id, t); types.put(t.id, t); }
    public boolean unregister(String id) { apiTypes.remove(id); return types.remove(id) != null; }

    public VehicleType get(String id) { return id == null ? null : types.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<VehicleType> all() { return types.values(); }
    public List<String> ids() { return new ArrayList<>(types.keySet()); }
    public void setRigs(Map<String, Rig> rigs) { this.rigs = rigs; }
    public Rig rig(String model) { return rigs.get(model); }
    public Rig rigOf(VehicleType t) { return rigs.get(t.model); }
}

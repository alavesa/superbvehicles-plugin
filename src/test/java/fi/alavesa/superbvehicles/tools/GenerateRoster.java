package fi.alavesa.superbvehicles.tools;

import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.pack.PackBuilder;
import fi.alavesa.superbvehicles.pack.PlaceholderModels;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Build-time tool: writes the placeholder .bbmodel of every shipped vehicle (src/main/resources/vehicles) into
 * src/main/resources/models and the defaults-index.txt the plugin extracts on first run.
 * Run: java -cp target/classes:target/test-classes:<paper-api jar> fi.alavesa.superbvehicles.tools.GenerateRoster
 */
public final class GenerateRoster {
    public static void main(String[] args) throws Exception {
        File res = new File(args.length > 0 ? args[0] : "src/main/resources");
        File vehicles = new File(res, "vehicles"), models = new File(res, "models");
        models.mkdirs();
        List<String> errors = new ArrayList<>(), index = new ArrayList<>();
        for (VehicleType t : VehicleType.loadAll(vehicles, errors)) {
            File f = new File(models, t.model + ".bbmodel");
            Files.writeString(f.toPath(), PlaceholderModels.forType(t));
            index.add("vehicles/" + t.source.getName());
            index.add("models/" + f.getName());
            System.out.println(t.id + " -> " + f.getName() + " (" + f.length() / 1024 + " KB)");
        }
        for (String p : PackBuilder.PROJECTILES) {
            File f = new File(models, p + ".bbmodel");
            Files.writeString(f.toPath(), PlaceholderModels.projectile(p));
            index.add("models/" + f.getName());
        }
        Files.writeString(new File(res, "defaults-index.txt").toPath(), String.join("\n", index) + "\n");
        for (String e : errors) System.err.println("ERROR " + e);
        System.out.println(index.size() + " default files indexed" + (errors.isEmpty() ? "" : ", " + errors.size() + " error(s)"));
        if (!errors.isEmpty()) System.exit(1);
    }
}

package fi.alavesa.superbvehicles.damage;

/** What lowered a vehicle's health. Armour only matters for the first three. */
public enum DamageType { KINETIC, EXPLOSIVE, MELEE, FIRE, COLLISION, FALL, OTHER;
    public boolean armourApplies() { return this == KINETIC || this == EXPLOSIVE || this == MELEE; }
}

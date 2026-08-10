package br.com.carvoeiromanager.staff;

import java.util.Set;

/**
 * The technical-staff roles. {@code TECNICO} is the head coach: it is seeded for AI clubs only —
 * at the managed club the head coach is the player, so no row exists and the slot is synthesised
 * from the save's manager name. The other four are hirable; none of them is ever labeled bare
 * "Técnico" in the UI, because that word already denotes the manager persona.
 */
public enum StaffRole {
    TECNICO, ASSISTENTE, PREPARADOR_FISICO, OLHEIRO, FISIOTERAPEUTA;

    /** The roles the player can hire or poach — everything except the head coach. */
    public static final Set<StaffRole> HIRABLE =
            Set.of(ASSISTENTE, PREPARADOR_FISICO, OLHEIRO, FISIOTERAPEUTA);
}

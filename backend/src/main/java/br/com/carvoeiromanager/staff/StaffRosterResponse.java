package br.com.carvoeiromanager.staff;

/**
 * The club's current roster, one slot per role — null means the role is unfilled.
 * {@code tecnico} is special: for the managed club it carries the player's own manager persona
 * (null quality and wage), because at your club the head coach is you.
 */
public record StaffRosterResponse(StaffResponse tecnico, StaffResponse assistente,
                                  StaffResponse preparadorFisico, StaffResponse olheiro,
                                  StaffResponse fisioterapeuta) {
}

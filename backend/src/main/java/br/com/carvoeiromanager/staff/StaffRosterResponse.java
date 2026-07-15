package br.com.carvoeiromanager.staff;

/** The club's current roster, one slot per role — null means the role is unfilled. */
public record StaffRosterResponse(StaffResponse assistente, StaffResponse preparadorFisico,
                                  StaffResponse olheiro, StaffResponse fisioterapeuta) {
}

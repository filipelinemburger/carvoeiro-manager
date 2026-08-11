import type { StaffRole, StaffQuality } from '../../api/types'

/**
 * Display names for the staff domain, shared by the manager's own Comissão Técnica page and the
 * commission block on a club's profile. Kept in one place because the two screens must agree:
 * renaming a role or an attribute in only one of them is a silent inconsistency no test would fail
 * on, since each screen asserts against its own copy.
 */
export const STAFF_ROLE_LABEL: Record<StaffRole, string> = {
  TECNICO: 'Técnico',
  ASSISTENTE: 'Auxiliar Técnico',
  PREPARADOR_FISICO: 'Preparador Físico',
  OLHEIRO: 'Olheiro',
  FISIOTERAPEUTA: 'Fisioterapeuta',
}

export const STAFF_QUALITY_LABEL: Record<StaffQuality, string> = {
  REGULAR: 'Regular',
  BOM: 'Bom',
  EXCELENTE: 'Excelente',
}

/** The two attributes each role is rated on, as [primary, secondary]. */
export const STAFF_ATTRIBUTE_LABELS: Record<StaffRole, [string, string]> = {
  TECNICO: ['Trabalho Tático', 'Motivação'],
  ASSISTENTE: ['Conhecimento Tático', 'Avaliação de Jogadores'],
  PREPARADOR_FISICO: ['Preparação Física', 'Ciência Esportiva'],
  OLHEIRO: ['Conhecimento do Mercado', 'Negociação'],
  FISIOTERAPEUTA: ['Prevenção de Lesões', 'Reabilitação'],
}

/** Head coach first: the most recognisable member of a comissão técnica leads the listing. */
export const STAFF_ROLE_ORDER: readonly StaffRole[] = [
  'TECNICO', 'ASSISTENTE', 'PREPARADOR_FISICO', 'OLHEIRO', 'FISIOTERAPEUTA',
]

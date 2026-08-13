import type { TrainableAttribute, TrainingIntensity } from '../../../api/types'

export const TRAINABLE_ATTRIBUTE_LABEL: Record<TrainableAttribute, string> = {
  VELOCIDADE: 'Velocidade',
  FINALIZACAO: 'Finalização',
  PASSE: 'Passe',
  DESARME: 'Desarme',
  REFLEXOS: 'Reflexos',
  FOLEGO: 'Fôlego',
  CABECEIO: 'Cabeceio',
  CRUZAMENTO: 'Cruzamento',
  MARCACAO: 'Marcação',
  DRIBLE: 'Drible',
  VISAO: 'Visão de jogo',
  FORCA: 'Força',
}

export const INTENSITY_LABEL: Record<TrainingIntensity, string> = {
  LEVE: 'Leve',
  NORMAL: 'Normal',
  INTENSA: 'Intensa',
}

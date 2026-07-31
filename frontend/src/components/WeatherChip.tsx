import { Chip } from '@mui/material'
import AcUnitIcon from '@mui/icons-material/AcUnit'
import AirIcon from '@mui/icons-material/Air'
import CloudIcon from '@mui/icons-material/Cloud'
import ThunderstormIcon from '@mui/icons-material/Thunderstorm'
import WaterDropIcon from '@mui/icons-material/WaterDrop'
import WbSunnyIcon from '@mui/icons-material/WbSunny'

export type WeatherCondition =
  | 'ENSOLARADO'
  | 'NUBLADO'
  | 'VENTANIA'
  | 'CHUVA'
  | 'CHUVA_FORTE'

const LABELS: Record<WeatherCondition, string> = {
  ENSOLARADO: 'Ensolarado',
  NUBLADO: 'Nublado',
  VENTANIA: 'Ventania',
  CHUVA: 'Chuva',
  CHUVA_FORTE: 'Chuva forte',
}

const ICONS: Record<WeatherCondition, typeof WbSunnyIcon> = {
  ENSOLARADO: WbSunnyIcon,
  NUBLADO: CloudIcon,
  VENTANIA: AirIcon,
  CHUVA: WaterDropIcon,
  CHUVA_FORTE: ThunderstormIcon,
}

type Props = {
  condition: WeatherCondition | null
  temperatureC: number | null
}

/** Match-day forecast. Renders nothing before the forecast is revealed (the eve of the match). */
export function WeatherChip({ condition, temperatureC }: Props) {
  if (!condition) {
    return null
  }
  const Icon = temperatureC !== null && temperatureC <= 8 ? AcUnitIcon : ICONS[condition]
  const label =
    temperatureC !== null ? `${LABELS[condition]} · ${temperatureC}°C` : LABELS[condition]

  return <Chip data-testid="weather-chip" size="small" icon={<Icon />} label={label} />
}

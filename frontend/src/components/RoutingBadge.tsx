interface Props { action: string }

const CONFIG: Record<string, { emoji: string; bg: string; label: string }> = {
  LOCAL:            { emoji: '🔴', bg: '#7f1d1d', label: 'LOCAL (on-device LLM)' },
  REDACT_THEN_CLOUD:{ emoji: '🟡', bg: '#78350f', label: 'REDACT → CLOUD' },
  CLOUD:            { emoji: '🟢', bg: '#14532d', label: 'CLOUD' },
  FUNCTION_GEMMA:   { emoji: '🟢', bg: '#1e3a5f', label: 'DEVICE ACTION' },
}

export function RoutingBadge({ action }: Props) {
  const cfg = CONFIG[action] ?? { emoji: '⚪', bg: '#374151', label: action }
  return (
    <span style={{
      background: cfg.bg,
      borderRadius: 20,
      padding: '4px 14px',
      fontWeight: 700,
      fontSize: 14,
      whiteSpace: 'nowrap',
    }}>
      {cfg.emoji} {cfg.label}
    </span>
  )
}

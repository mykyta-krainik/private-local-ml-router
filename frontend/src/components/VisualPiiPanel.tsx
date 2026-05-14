import { VisualEntity } from '../types'

const TYPE_COLOR: Record<string, string> = {
  FACE: '#7c3aed',
  DOCUMENT_ID: '#b45309',
  CARD_PAYMENT: '#dc2626',
  LICENSE_PLATE: '#0369a1',
  SCREEN: '#0f766e',
  MEDICAL_DOC: '#be185d',
  HANDWRITTEN_FORM: '#4d7c0f',
}

const TYPE_ICON: Record<string, string> = {
  FACE: '👤',
  DOCUMENT_ID: '🪪',
  CARD_PAYMENT: '💳',
  LICENSE_PLATE: '🚗',
  SCREEN: '🖥',
  MEDICAL_DOC: '🏥',
  HANDWRITTEN_FORM: '📝',
}

interface Props {
  entities: VisualEntity[]
}

export function VisualPiiPanel({ entities }: Props) {
  if (entities.length === 0) {
    return (
      <div style={{ color: '#64748b', fontSize: 13, fontStyle: 'italic' }}>
        No visual PII detected
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      {entities.map((e, i) => {
        const color = TYPE_COLOR[e.type] ?? '#475569'
        const icon = TYPE_ICON[e.type] ?? '⚠️'
        return (
          <div
            key={i}
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              background: color + '22',
              border: `1px solid ${color}55`,
              borderRadius: 6, padding: '4px 10px',
            }}
          >
            <span style={{ fontSize: 15 }}>{icon}</span>
            <span style={{ fontSize: 12, fontWeight: 600, color }}>
              {e.type.replace(/_/g, ' ')}
            </span>
            <span style={{
              fontSize: 11, color: '#94a3b8',
              background: '#0f172a', borderRadius: 4, padding: '1px 5px',
            }}>
              {(e.confidence * 100).toFixed(0)}%
            </span>
            <span style={{ fontSize: 10, color: '#475569' }}>
              {e.source.replace(/_/g, ' ')}
            </span>
          </div>
        )
      })}
    </div>
  )
}

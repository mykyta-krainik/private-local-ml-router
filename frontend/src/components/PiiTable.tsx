interface Entity { text: string; type: string; confidence: number; source: string }

interface Props {
  entities: Entity[]
  signals: string[]
}

const TYPE_COLOR: Record<string, string> = {
  HEALTH: '#dc2626', FINANCIAL: '#ea580c', PHONE: '#ca8a04',
  ADDRESS: '#16a34a', EMAIL: '#0891b2', PERSON: '#7c3aed',
  LOCATION: '#2563eb', ORGANIZATION: '#059669', DATE_TIME: '#64748b', MISC: '#6b7280',
}

export function PiiTable({ entities, signals }: Props) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {signals.length > 0 && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {signals.map(s => (
            <span key={s} style={{
              background: '#312e81', borderRadius: 12, padding: '2px 10px', fontSize: 12,
            }}>{s}</span>
          ))}
        </div>
      )}

      {entities.length === 0 ? (
        <p style={{ color: '#64748b', fontSize: 13 }}>No PII entities detected</p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ color: '#94a3b8', textAlign: 'left' }}>
              <th style={{ padding: '4px 8px' }}>Text</th>
              <th style={{ padding: '4px 8px' }}>Type</th>
              <th style={{ padding: '4px 8px' }}>Confidence</th>
              <th style={{ padding: '4px 8px' }}>Source</th>
            </tr>
          </thead>
          <tbody>
            {entities.map((e, i) => (
              <tr key={i} style={{ borderTop: '1px solid #1e2130' }}>
                <td style={{ padding: '4px 8px', fontFamily: 'monospace' }}>"{e.text}"</td>
                <td style={{ padding: '4px 8px' }}>
                  <span style={{
                    background: TYPE_COLOR[e.type] ?? '#374151',
                    borderRadius: 4, padding: '1px 7px', fontSize: 11, fontWeight: 600,
                  }}>{e.type}</span>
                </td>
                <td style={{ padding: '4px 8px', color: '#94a3b8' }}>
                  {(e.confidence * 100).toFixed(0)}%
                </td>
                <td style={{ padding: '4px 8px', color: '#94a3b8' }}>{e.source}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

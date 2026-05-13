import { useState } from 'react'
import { ProcessResponse } from '../types'
import { PiiTable } from './PiiTable'
import { RoutingBadge } from './RoutingBadge'

interface Props { result: ProcessResponse }

const card: React.CSSProperties = {
  background: '#1e2130', borderRadius: 10, padding: '14px 18px',
  display: 'flex', flexDirection: 'column', gap: 10,
}
const heading: React.CSSProperties = {
  fontSize: 11, fontWeight: 700, letterSpacing: '0.08em',
  textTransform: 'uppercase', color: '#64748b', marginBottom: 4,
}
const pill = (color: string): React.CSSProperties => ({
  background: color, borderRadius: 6, padding: '2px 10px',
  fontSize: 13, fontWeight: 600, display: 'inline-block',
})

function Collapsible({ title, children }: { title: string; children: React.ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <div>
      <button
        onClick={() => setOpen(o => !o)}
        style={{
          background: 'transparent', color: '#94a3b8', padding: '4px 0',
          fontSize: 13, fontWeight: 500, textAlign: 'left',
        }}
      >
        {open ? '▾' : '▸'} {title}
      </button>
      {open && <div style={{ marginTop: 8 }}>{children}</div>}
    </div>
  )
}

function row(label: string, value: React.ReactNode) {
  return (
    <div key={label} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, gap: 8 }}>
      <span style={{ color: '#94a3b8' }}>{label}</span>
      <span style={{ fontWeight: 500 }}>{value}</span>
    </div>
  )
}

export function PipelineFlow({ result }: Props) {
  const { stages, totalLatencyMs } = result
  const s1 = stages.stage1_classification
  const s2 = stages.stage2_piiDetection
  const s3s = stages.stage3_scoring
  const s3r = stages.stage3_routing
  const exec = stages.execution

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

      {/* Flow strip */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        flexWrap: 'wrap', padding: '12px 0', fontSize: 13,
      }}>
        <span style={pill('#1e3a5f')}>Stage 1 · {s1.label} ({(s1.confidence * 100).toFixed(0)}%)</span>
        <span style={{ color: '#475569' }}>→</span>
        <span style={pill('#172840')}>
          Stage 2 · {s2.entities.length} entities{s2.signals.length ? `, ${s2.signals.length} signals` : ''}
        </span>
        <span style={{ color: '#475569' }}>→</span>
        <span style={pill('#1a2744')}>Score {s3s.finalScore.toFixed(2)}</span>
        <span style={{ color: '#475569' }}>→</span>
        <RoutingBadge action={s3r.action} />
        <span style={{ color: '#475569' }}>→</span>
        <span style={{ color: '#64748b', fontSize: 12 }}>{totalLatencyMs} ms</span>
      </div>

      {/* Stage 1 */}
      <div style={card}>
        <p style={heading}>Stage 1 — Classification</p>
        {row('Label', <span style={pill('#1e3a5f')}>{s1.label}</span>)}
        {row('Confidence', `${(s1.confidence * 100).toFixed(1)}%`)}
        {row('Tier', `${s1.tierId} (${s1.tier})`)}
      </div>

      {/* Stage 2 */}
      <div style={card}>
        <p style={heading}>Stage 2 — PII Detection</p>
        {row('Mode', s2.mode)}
        {row('Tiers used', s2.tiersUsed.join(', ') || 'none')}
        {row('Latency', `${s2.latencyMs} ms`)}
        <Collapsible title={`${s2.entities.length} entities · ${s2.signals.length} signals`}>
          <PiiTable entities={s2.entities} signals={s2.signals} />
        </Collapsible>
      </div>

      {/* Stage 3 scoring */}
      <div style={card}>
        <p style={heading}>Stage 3 — Sensitivity Score</p>
        {row('Entity score', s3s.entityScore.toFixed(3))}
        {row('Signal boost', `+${s3s.signalBoost.toFixed(3)}`)}
        {row('Label multiplier', `×${s3s.labelMultiplier.toFixed(2)}`)}
        {row('Final score', <strong>{s3s.finalScore.toFixed(3)}</strong>)}
      </div>

      {/* Stage 3 routing */}
      <div style={card}>
        <p style={heading}>Stage 3 — Routing Decision</p>
        {row('Action', <RoutingBadge action={s3r.action} />)}
        {row('Sensitivity score', s3r.sensitivityScore.toFixed(3))}
        {row('Rule fired', s3r.firedRule ?? '—')}
      </div>

      {/* Execution */}
      <div style={card}>
        <p style={heading}>Execution Result</p>
        {exec.type === 'text' && (
          <p style={{ fontSize: 14, lineHeight: 1.6, whiteSpace: 'pre-wrap' }}>{exec.body}</p>
        )}
        {exec.type === 'action' && (
          <>
            {row('Type', 'Device action')}
            <p style={{ fontSize: 14, color: '#86efac', marginTop: 4 }}>✓ {exec.functionDescription}</p>
          </>
        )}
        {(exec.type === 'error' || exec.type === 'action_error' || exec.type === 'action_unknown') && (
          <p style={{ fontSize: 13, color: '#fca5a5' }}>⚠ {exec.error}</p>
        )}
      </div>
    </div>
  )
}

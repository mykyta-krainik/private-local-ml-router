import { useState } from 'react'
import { PipelineFlow } from './components/PipelineFlow'
import { ProcessResponse } from './types'

const EXAMPLES = [
  'set a timer for 5 minutes',
  'my doctor prescribed medication for my anxiety',
  'what is the capital of France?',
  'call my therapist',
  'transfer $500 from my bank account to alice@example.com',
]

export default function App() {
  const [text, setText] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<ProcessResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [showRaw, setShowRaw] = useState(false)

  async function submit() {
    if (!text.trim()) return
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      const res = await fetch('/api/process', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text }),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.error ?? `HTTP ${res.status}`)
      }
      setResult(await res.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  function handleKey(e: React.KeyboardEvent) {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) submit()
  }

  return (
    <div style={{ maxWidth: 820, margin: '0 auto', padding: '32px 16px' }}>
      <h1 style={{ fontSize: 22, fontWeight: 700, marginBottom: 4 }}>
        Privacy Router — Demo
      </h1>
      <p style={{ color: '#94a3b8', fontSize: 14, marginBottom: 24 }}>
        Type a query to see how the privacy pipeline classifies, detects PII, scores, and routes it.
      </p>

      {/* Examples */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        {EXAMPLES.map(ex => (
          <button
            key={ex}
            onClick={() => setText(ex)}
            style={{
              background: '#1e2130', color: '#94a3b8', fontSize: 12,
              padding: '4px 10px', border: '1px solid #2d3348',
            }}
          >
            {ex}
          </button>
        ))}
      </div>

      {/* Input */}
      <textarea
        rows={3}
        placeholder="Enter a query… (Ctrl+Enter to submit)"
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={handleKey}
      />
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
        <button onClick={submit} disabled={loading || !text.trim()}>
          {loading ? 'Processing…' : 'Process →'}
        </button>
      </div>

      {/* Error */}
      {error && (
        <div style={{
          marginTop: 20, background: '#7f1d1d', borderRadius: 8, padding: '12px 16px', color: '#fca5a5',
        }}>
          {error}
        </div>
      )}

      {/* Result */}
      {result && (
        <div style={{ marginTop: 28 }}>
          <PipelineFlow result={result} />

          {/* Raw JSON toggle */}
          <div style={{ marginTop: 16 }}>
            <button
              onClick={() => setShowRaw(v => !v)}
              style={{ background: '#1e2130', color: '#64748b', fontSize: 12, padding: '4px 12px' }}
            >
              {showRaw ? 'Hide' : 'Show'} raw JSON
            </button>
            {showRaw && <pre style={{ marginTop: 8 }}>{JSON.stringify(result, null, 2)}</pre>}
          </div>
        </div>
      )}
    </div>
  )
}

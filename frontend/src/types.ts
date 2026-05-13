export interface ProcessResponse {
  stages: {
    stage1_classification: {
      tierId: number
      tier: string
      label: string
      confidence: number
    }
    stage2_piiDetection: {
      mode: string
      entities: Array<{ text: string; type: string; confidence: number; source: string }>
      signals: string[]
      tiersUsed: string[]
      latencyMs: number
    }
    stage3_scoring: {
      entityScore: number
      signalBoost: number
      labelMultiplier: number
      finalScore: number
    }
    stage3_routing: {
      action: string
      sensitivityScore: number
      firedRule: string | null
    }
    execution: {
      type: string
      body: string | null
      functionDescription: string | null
      error: string | null
    }
  }
  totalLatencyMs: number
}

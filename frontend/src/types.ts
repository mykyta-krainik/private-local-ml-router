export interface VisualEntity {
  type: string
  confidence: number
  source: string
}

export interface ProcessResponse {
  stages: {
    stage1_classification: {
      tierId: number
      tier: string
      label: string
      confidence: number
      matchedPattern: string | null
      classificationLatencyMs: number
    }
    stage2_piiDetection: {
      mode: string
      entities: Array<{ text: string; type: string; confidence: number; source: string }>
      signals: string[]
      tiersUsed: string[]
      latencyMs: number
      tier0EntityCount: number
      tier1EntityCount: number
      sharedEntityCount: number
      visualEntityCount: number
      visualEntities: VisualEntity[]
    }
    stage3_scoring: {
      entityScore: number
      signalBoost: number
      labelMultiplier: number
      finalScore: number
      scoreZone: string
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
  requestIndex: number
}

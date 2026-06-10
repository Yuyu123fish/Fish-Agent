import { authFetch } from './http'

export interface CardRelationItem {
  id: number
  cardId: number
  cardTitle: string
  cardType?: 'concept' | 'topic'
  reviewStatus?: 'mastered' | 'learning' | 'new' | null
  relationType: string
  confidence: number
  direction: 'incoming' | 'outgoing'
}

export interface RelatedChunkItem {
  taskId: string
  fileName: string
  chunkIndex: number
  contentPreview: string
  similarity: number
}

export interface CardDetail {
  id: number
  title: string
  content: string
  keywords: string[]
  cardType: 'concept' | 'topic'
  sourceType: string
  sourceId?: string | null
  status: 'confirmed' | 'pending' | 'rejected'
  groupName?: string | null
  groupId?: number | null
  groupPath?: string | null
  relations: CardRelationItem[]
  relatedChunks?: RelatedChunkItem[]
  createdAt?: string | null
  updatedAt?: string | null
  reviewInfo?: ReviewInfo | null
}

export interface CardListItem {
  id: number
  title: string
  contentPreview: string
  keywords: string[]
  cardType: 'concept' | 'topic'
  sourceType: string
  status: 'confirmed' | 'pending' | 'rejected'
  groupName?: string | null
  groupId?: number | null
  relationCount: number
  reviewNextAt?: string | null
  reviewCount: number
  createdAt?: string | null
}

export interface CardPage {
  records: CardListItem[]
  total: number
  current: number
  size: number
}

export interface GroupTreeNode {
  id: number
  name: string
  cardCount: number
  children: GroupTreeNode[]
}

export interface CardStats {
  total: number
  confirmed: number
  pending: number
  relationCount: number
  weekNew: number
  groups: GroupTreeNode[]
}

export interface ExtractRelation {
  id: number
  fromCardId: number
  toCardId: number
  relationType: string
  confidence: number
}

export interface ExtractResult {
  extractedCount: number
  cardIds: number[]
  cards: CardDetail[]
  relations: ExtractRelation[]
}

export interface RelationSuggestion {
  fromCardId: number
  fromTitle: string
  toCardId: number
  toTitle: string
  suggestedType: string
  confidence: number
  reasons: string[]
}

export interface DiscoverResult {
  suggestions: RelationSuggestion[]
  total: number
}

export interface ConfirmDiscoveredRelation {
  fromCardId: number
  toCardId: number
  relationType: string
}

export interface CardPayload {
  title: string
  content: string
  keywords: string[]
  cardType: 'concept' | 'topic'
  groupName?: string | null
  groupId?: number | null
}

export interface CardListQuery {
  page?: number
  size?: number
  status?: string
  keyword?: string
  groupName?: string
  groupId?: number | null
  cardType?: string
  reviewOverdue?: boolean
  sortBy?: string
  sortOrder?: string
}

export interface ReviewInfo {
  nextReviewAt: string | null
  reviewCount: number
  lastReviewedAt: string | null
  easinessFactor: number
  intervalDays: number
  repetition: number
}

export interface ReviewCardVO {
  id: number
  title: string
  content: string
  keywords: string[]
  cardType: 'concept' | 'topic'
  groupPath: string | null
  reviewInfo: ReviewInfo | null
}

export interface ReviewQueueResponse {
  cards: ReviewCardVO[]
  totalDue: number
  totalNew: number
}

export interface ReviewAnswerResponse {
  nextReviewAt: string
  intervalDays: number
  easinessFactor: number
  remainingDue: number
}

export interface ReviewStatsResponse {
  totalCards: number
  mastered: number
  learning: number
  dueToday: number
  streakDays: number
  reviewCalendar: Record<string, number>
  weeklyActivity: number[]
}

async function parseError(r: Response): Promise<string> {
  const data = await r.json().catch(() => ({}))
  return (data as { message?: string })?.message ?? `HTTP ${r.status}`
}

export async function createCard(payload: CardPayload): Promise<{ id: number }> {
  const r = await authFetch('/api/card', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as { id: number }
}

export async function extractCards(sessionId: string): Promise<ExtractResult> {
  const r = await authFetch(`/api/card/extract/${encodeURIComponent(sessionId)}`, {
    method: 'POST'
  })
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as ExtractResult
}

export async function listCards(query: CardListQuery = {}): Promise<CardPage> {
  const q = new URLSearchParams()
  q.set('page', String(query.page ?? 1))
  q.set('size', String(query.size ?? 20))
  if (query.status && query.status !== 'all') q.set('status', query.status)
  if (query.keyword?.trim()) q.set('keyword', query.keyword.trim())
  if (query.groupName && query.groupName !== 'all') q.set('groupName', query.groupName)
  if (query.groupId && query.groupId > 0) q.set('groupId', String(query.groupId))
  if (query.cardType && query.cardType !== 'all') q.set('cardType', query.cardType)
  if (query.reviewOverdue) q.set('reviewOverdue', 'true')
  if (query.sortBy && query.sortBy !== 'default') q.set('sortBy', query.sortBy)
  if (query.sortOrder) q.set('sortOrder', query.sortOrder)
  const r = await authFetch(`/api/card/list?${q}`)
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as CardPage
}

export async function getReviewQueue(groupId?: number): Promise<ReviewQueueResponse> {
  const q = new URLSearchParams()
  if (groupId && groupId > 0) q.set('groupId', String(groupId))
  const suffix = q.toString()
  const r = await authFetch(`/api/card/review/queue${suffix ? `?${suffix}` : ''}`)
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as ReviewQueueResponse
}

export async function submitReviewAnswer(cardId: number, quality: number): Promise<ReviewAnswerResponse> {
  const r = await authFetch('/api/card/review/answer', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cardId, quality })
  })
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as ReviewAnswerResponse
}

export async function getReviewStats(): Promise<ReviewStatsResponse> {
  const r = await authFetch('/api/card/review/stats')
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as ReviewStatsResponse
}

export async function getCard(id: number): Promise<CardDetail> {
  const r = await authFetch(`/api/card/${id}`)
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as CardDetail
}

export async function updateCard(id: number, payload: CardPayload): Promise<void> {
  const r = await authFetch(`/api/card/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!r.ok) throw new Error(await parseError(r))
}

export async function batchConfirmCards(ids: number[]): Promise<void> {
  const r = await authFetch('/api/card/batch-confirm', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids })
  })
  if (!r.ok) throw new Error(await parseError(r))
}

export async function batchRejectCards(ids: number[]): Promise<void> {
  const r = await authFetch('/api/card/batch-reject', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids })
  })
  if (!r.ok) throw new Error(await parseError(r))
}

export async function mergeCards(keepId: number, discardId: number): Promise<void> {
  const r = await authFetch('/api/card/merge', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keepId, discardId })
  })
  if (!r.ok) throw new Error(await parseError(r))
}

export async function addCardRelation(cardId: number, toCardId: number, relationType: string): Promise<{ id: number }> {
  const r = await authFetch(`/api/card/${cardId}/relation`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ toCardId, relationType })
  })
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as { id: number }
}

export async function listAllCardRelations(): Promise<ExtractRelation[]> {
  const r = await authFetch('/api/card/all-relations')
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as ExtractRelation[]
}

export async function discoverRelations(): Promise<DiscoverResult> {
  const r = await authFetch('/api/card/discover-relations', { method: 'POST' })
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as DiscoverResult
}

export async function confirmDiscoveredRelations(relations: ConfirmDiscoveredRelation[]): Promise<void> {
  const r = await authFetch('/api/card/confirm-discovered-relations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(relations)
  })
  if (!r.ok) throw new Error(await parseError(r))
}

export async function migrateKeywords(): Promise<{ success: boolean; migrated: number }> {
  const r = await authFetch('/api/card/migrate-keywords', { method: 'POST' })
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as { success: boolean; migrated: number }
}

export async function deleteCard(id: number): Promise<void> {
  const r = await authFetch(`/api/card/${id}`, { method: 'DELETE' })
  if (!r.ok) throw new Error(await parseError(r))
}

export async function getCardStats(): Promise<CardStats> {
  const r = await authFetch('/api/card/stats')
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as CardStats
}

export async function getCardGroups(): Promise<GroupTreeNode[]> {
  const r = await authFetch('/api/card/groups')
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as GroupTreeNode[]
}

export async function migrateGroups(): Promise<{ success: boolean; migrated: number }> {
  const r = await authFetch('/api/card/migrate-groups', { method: 'POST' })
  if (!r.ok) throw new Error(await parseError(r))
  return (await r.json()) as { success: boolean; migrated: number }
}

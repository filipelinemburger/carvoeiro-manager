# Frontend Caching / Network-Request Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut redundant frontend API calls by moving Squad, News, Standings, Market, Free Agents, Season History, and Base off TanStack Query's default `staleTime: 0` onto `staleTime: Infinity` plus explicit invalidation/write-through/optimistic-update wiring, per `docs/superpowers/specs/2026-07-16-frontend-caching-design.md`.

**Architecture:** Every targeted `useQuery` gains `staleTime: Infinity` so it only refetches when a mutation explicitly tells it to. Each mutation that can change a cached resource is audited: existing-and-correct triggers are left alone, missing ones ("gaps") are added, and where a mutation's own response already contains the query's exact data shape, `queryClient.setQueryData` write-through replaces `invalidateQueries` to skip a redundant round-trip. The Market page's `offers` cache additionally gets true optimistic updates (`onMutate`/`onError`/`onSettled`) for the two manager-decided actions (withdraw, accept/reject an incoming offer). News moves from a single unpaginated fetch to a real paginated backend endpoint consumed via `useInfiniteQuery`.

**Tech Stack:** React 19, TanStack Query v5, MUI, Spring Boot 3 (Java 21), the existing `PageResponse<T>` pagination helper.

## Global Constraints

- Every targeted query moves to `staleTime: Infinity`, set **per-query** via each `useQuery`/`useInfiniteQuery` call's own options — never via a global `QueryClient` default change (`frontend/src/main.tsx` stays untouched).
- Background image (`frontend/src/theme.css`) is out of scope — no file in this plan touches it.
- True optimistic updates (`onMutate` snapshot + `onError` rollback + `onSettled` reconcile) are used **only** for `withdrawMut` and `respondMut`'s `ACCEPT`/`REJECT` branches on the Market page's `['offers', saveGameId]` cache — nowhere else in this plan.
- Write-through (`queryClient.setQueryData` from an already-resolved mutation response, replacing `invalidateQueries`) is used for exactly four spots: `setTraining` → `['squad', saveGameId, clubId]`, `setLineup` → `['lineup', saveGameId, clubId]`, and `offerMut`/`acceptMut`/`respondMut`'s `COUNTER` branch → `['offers', saveGameId]`.
- News pagination reuses `PageResponse.of(list, page, size)` (`backend/src/main/java/br/com/carvoeiromanager/common/PageResponse.java`) exactly as the Market endpoint (`TransferController.market`) already does — no new pagination helper.
- News page size defaults to 10, page defaults to 1 (1-based), mirroring the Market endpoint's own defaults/convention.
- Full suites at the end of every task: backend `./gradlew test` (heap already 3g via `backend/build.gradle`), frontend `npx tsc --noEmit && npx vitest run`.

---

## Task 1: Backend — News pagination endpoint

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/AdvanceController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/NewsFeedIT.java`

**Interfaces:**
- Produces: `GET /api/v1/save-games/{saveGameId}/news?page=&size=` now returns `PageResponse<AdvanceResponse>` (was `List<AdvanceResponse>`), `page` defaults to `1`, `size` defaults to `10`.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/br/com/carvoeiromanager/season/NewsFeedIT.java`. First add one import and one autowired field, then append three new `@Test` methods (and one private helper) before the class's closing brace:

```java
import br.com.carvoeiromanager.common.PageResponse;
```

```java
    @Autowired AdvanceController advanceController;
```

```java
    /** Persists {@code count} minimal MATCH news items (seq 1..count) without driving a real
     *  calendar advance — isolates the pagination math from how many events a season has. */
    private void persistNewsItems(UUID saveGameId, int count) {
        AdvanceResponse minimal = new AdvanceResponse("MATCH", null, null, null, List.of(), List.of(),
                List.of(), List.of(), null, 0, false, null, null, null);
        for (int seq = 1; seq <= count; seq++) {
            String payload;
            try {
                payload = objectMapper.writeValueAsString(minimal);
            } catch (Exception e) {
                throw new AssertionError("Failed to serialize test news payload", e);
            }
            newsItemRepository.save(NewsItem.create(saveGameId, seq, null, "MATCH", payload));
        }
    }

    @Test
    void newsFeedDefaultsToPageOneSizeTen() {
        Career c = createCareer();
        persistNewsItems(c.id(), 15);

        PageResponse<AdvanceResponse> page = advanceController.getNews(c.id(), c.userId(), 1, 10).getBody();

        assertThat(page.content()).hasSize(10);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(15);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void newsFeedSecondPageReturnsTheRemainingOlderItems() {
        Career c = createCareer();
        persistNewsItems(c.id(), 15);

        PageResponse<AdvanceResponse> page = advanceController.getNews(c.id(), c.userId(), 2, 10).getBody();

        assertThat(page.content()).hasSize(5);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(15);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void newsFeedOutOfRangePageReturnsEmptyContentWithCorrectTotals() {
        Career c = createCareer();
        persistNewsItems(c.id(), 3);

        PageResponse<AdvanceResponse> page = advanceController.getNews(c.id(), c.userId(), 5, 10).getBody();

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.season.NewsFeedIT"`
Expected: FAIL to compile — `AdvanceController.getNews(UUID, UUID, int, int)` does not exist yet (current signature is `getNews(UUID, UUID)`), and `newsItemRepository`/`objectMapper` fields already exist so only the new method signature is missing.

- [ ] **Step 3: Implement the pagination endpoint**

In `backend/src/main/java/br/com/carvoeiromanager/season/AdvanceController.java`, add two imports next to the existing `org.springframework.web.bind.annotation.*` imports:

```java
import br.com.carvoeiromanager.common.PageResponse;
```
```java
import org.springframework.web.bind.annotation.RequestParam;
```

Replace the existing `getNews` method:

```java
    @GetMapping("/{saveGameId}/news")
    public ResponseEntity<List<AdvanceResponse>> getNews(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId);
        List<AdvanceResponse> responses = items.stream()
                .map(item -> {
                    try {
                        return objectMapper.readValue(item.getPayload(), AdvanceResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize news item " + item.getId(), e);
                    }
                })
                .toList();
        return ResponseEntity.ok(responses);
    }
```

with:

```java
    @GetMapping("/{saveGameId}/news")
    public ResponseEntity<PageResponse<AdvanceResponse>> getNews(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        saveGameService.requireOwned(saveGameId, userId);
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId);
        List<AdvanceResponse> responses = items.stream()
                .map(item -> {
                    try {
                        return objectMapper.readValue(item.getPayload(), AdvanceResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize news item " + item.getId(), e);
                    }
                })
                .toList();
        return ResponseEntity.ok(PageResponse.of(responses, page, size));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.season.NewsFeedIT"`
Expected: PASS — all 6 tests (3 existing + 3 new) green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/AdvanceController.java backend/src/test/java/br/com/carvoeiromanager/season/NewsFeedIT.java
git commit -m "feat(news): paginate GET /news via the existing PageResponse helper"
```

---

## Task 2: Frontend — News infinite scroll

**Files:**
- Modify: `frontend/src/api/career.ts:104-106`
- Modify: `frontend/src/features/news/NewsPage.tsx`
- Test: `frontend/src/features/news/NewsPage.test.tsx`

**Interfaces:**
- Consumes: Task 1's `GET /news?page=&size=` returning `PageResponse<AdvanceResponse>`.
- Produces: `getNews(saveGameId: string, page: number, size: number, token: string): Promise<PageResponse<AdvanceResponse>>` (was `getNews(saveGameId: string, token: string): Promise<AdvanceResponse[]>`).

- [ ] **Step 1: Write the failing tests**

Replace the two existing `getNews` mocks in `frontend/src/features/news/NewsPage.test.tsx` (lines 83 and 91) — wrap each array in a `PageResponse` shape:

```ts
    vi.mocked(getNews).mockResolvedValue({ content: [TRANSFER_ITEM_WITH_TRADES], page: 1, size: 10, totalElements: 1, totalPages: 1 })
```
```ts
    vi.mocked(getNews).mockResolvedValue({ content: [TRANSFER_ITEM_WITHOUT_TRADES], page: 1, size: 10, totalElements: 1, totalPages: 1 })
```

Then append two new tests at the end of the `describe('NewsPage', ...)` block, before its closing `})`:

```ts
  it('loads more news items when "Carregar mais" is clicked, and hides the button once exhausted', async () => {
    const page1 = Array.from({ length: 10 }, (_, i) => ({ ...TRANSFER_ITEM_WITH_TRADES, date: `2026-07-${10 - i}` }))
    const page2 = [{ ...TRANSFER_ITEM_WITHOUT_TRADES, date: '2026-06-01' }]
    vi.mocked(getNews).mockImplementation((_saveGameId, page) =>
      Promise.resolve(
        page === 1
          ? { content: page1, page: 1, size: 10, totalElements: 11, totalPages: 2 }
          : { content: page2, page: 2, size: 10, totalElements: 11, totalPages: 2 },
      ),
    )
    renderPage()

    await waitFor(() => expect(screen.getAllByText('Transferências')).toHaveLength(10))
    const loadMore = screen.getByRole('button', { name: /carregar mais/i })

    fireEvent.click(loadMore)

    await waitFor(() => expect(screen.getAllByText('Transferências')).toHaveLength(11))
    expect(getNews).toHaveBeenCalledWith('sg-1', 2, 10, 'test-token')
    expect(screen.queryByRole('button', { name: /carregar mais/i })).not.toBeInTheDocument()
  })

  it('does not refetch news on a second mount when the query client is reused', async () => {
    vi.mocked(getNews).mockResolvedValue({ content: [TRANSFER_ITEM_WITH_TRADES], page: 1, size: 10, totalElements: 1, totalPages: 1 })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={['/news?saveGameId=sg-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <NewsPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByText('Transferências')).toBeInTheDocument())
    expect(getNews).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByText('Transferências')).toBeInTheDocument())
    expect(getNews).toHaveBeenCalledTimes(1)
  })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/news/NewsPage.test.tsx`
Expected: FAIL — `getNews` mock now resolves a `PageResponse` shape but `NewsPage` still calls the old `useQuery`/`AdvanceResponse[]` path, so `items` becomes `undefined`/wrongly-shaped and the existing render assertions break; the two new tests fail outright (no "Carregar mais" button exists yet).

- [ ] **Step 3: Implement**

In `frontend/src/api/career.ts`, replace the `getNews` function (lines 104-106):

```ts
// Persisted news feed: every advance is stored and returned here, most recent first.
export function getNews(saveGameId: string, token: string): Promise<AdvanceResponse[]> {
  return apiGet<AdvanceResponse[]>(`/save-games/${saveGameId}/news`, token)
}
```

with:

```ts
// Persisted news feed, paginated newest-first; page is 1-based.
export function getNews(
  saveGameId: string,
  page: number,
  size: number,
  token: string,
): Promise<PageResponse<AdvanceResponse>> {
  return apiGet<PageResponse<AdvanceResponse>>(`/save-games/${saveGameId}/news?page=${page}&size=${size}`, token)
}
```

Replace the full contents of `frontend/src/features/news/NewsPage.tsx`:

```tsx
import { useQuery, useInfiniteQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { Box, Button, Stack, Typography } from '@mui/material'
import { getNews, getSaveGame } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { AdvanceEventCard } from '../match/AdvanceEventCard'
import type { AdvanceResponse, PageResponse, SaveGameResponse } from '../../api/types'

const NEWS_PAGE_SIZE = 10

/**
 * Persisted news feed. Every season advance (match round, transfer window, or
 * season end) is stored server-side and shown here, most recent first, using the
 * same timeline rendering as the live advance result. Loaded 10 at a time via
 * "Carregar mais" — only the loaded pages live in the client cache.
 */
export function NewsPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''

  const { data: saveGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })

  const {
    data, isLoading, error, fetchNextPage, hasNextPage, isFetchingNextPage,
  } = useInfiniteQuery<PageResponse<AdvanceResponse>>({
    queryKey: ['news', saveGameId],
    queryFn: ({ pageParam }) => getNews(saveGameId, pageParam as number, NEWS_PAGE_SIZE, token ?? ''),
    initialPageParam: 1,
    getNextPageParam: (lastPage) => (lastPage.page < lastPage.totalPages ? lastPage.page + 1 : undefined),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })

  if (isLoading) return <Typography>Carregando notícias…</Typography>
  if (error) return <Typography color="error">Erro ao carregar notícias.</Typography>

  const competitionId = saveGame?.competitionId ?? ''
  const managedClubId = saveGame?.managedClubId ?? ''
  const items = (data?.pages ?? []).flatMap(p => p.content)

  return (
    <Box>
      <Typography variant="h5" component="h2" sx={{ fontWeight: 700, mt: 0, mb: 2 }}>
        Notícias
      </Typography>

      {items.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          Nenhuma notícia ainda. Clique em <Box component="strong">Avançar</Box> para jogar a próxima rodada.
        </Typography>
      ) : (
        <Stack spacing={2}>
          {items.map((item, i) => (
            <AdvanceEventCard
              // Feed is newest-first and server-ordered; a composite of the event's
              // identifying fields plus the index gives stable, unique keys.
              key={`${item.type}-${item.date ?? 'na'}-${item.round ?? 'na'}-${i}`}
              result={item}
              saveGameId={saveGameId}
              competitionId={competitionId}
              managedClubId={managedClubId}
            />
          ))}
        </Stack>
      )}

      {hasNextPage && (
        <Box sx={{ display: 'flex', justifyContent: 'center', mt: 2 }}>
          <Button variant="outlined" disabled={isFetchingNextPage} onClick={() => fetchNextPage()}>
            {isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}
          </Button>
        </Box>
      )}
    </Box>
  )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/news/NewsPage.test.tsx && npx tsc --noEmit`
Expected: PASS — all 4 tests green, no type errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/career.ts frontend/src/features/news/NewsPage.tsx frontend/src/features/news/NewsPage.test.tsx
git commit -m "feat(news): switch to paginated infinite-scroll feed with staleTime: Infinity"
```

---

## Task 3: Squad / Escalação caching

**Files:**
- Modify: `frontend/src/features/squad/SquadPage.tsx`
- Modify: `frontend/src/features/squad/TacticsPanel.tsx`
- Modify: `frontend/src/features/contract/FreeAgentsPage.tsx`
- Test: `frontend/src/features/squad/SquadPage.test.tsx`
- Test: `frontend/src/features/squad/TacticsPanel.test.tsx`
- Test: `frontend/src/features/contract/FreeAgentsPage.test.tsx`

**Interfaces:**
- Consumes: `setTraining` (`SquadResponse`), `setLineup` (`LineupResponse`) — both already return the exact shape of an existing query, per `frontend/src/api/career.ts:65-72,79-86`.

- [ ] **Step 1: Write the failing tests**

In `frontend/src/features/squad/SquadPage.test.tsx`, replace the existing test **"invalidates the lineup query after saving so the advance gate refreshes"** (lines 292-318) with a write-through proof:

```ts
  it('writes the saved lineup directly into the lineup cache so the advance gate refreshes without a refetch', async () => {
    // Regression: the layout's "Escale seu time" warning + disabled Avançar are driven by the
    // ['lineup', saveGameId, clubId] query. Saving a lineup must update it, otherwise the
    // warning persists and Avançar stays disabled until a full page reload — now done via a
    // direct cache write (write-through) instead of invalidate-then-refetch.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mockSetLineup.mockResolvedValue({
      lineupId: 'l-2', clubId: 'club-1', formation: '4-3-3',
      starters: [{ playerId: GK.id, role: 'GOALKEEPER' }], bench: [],
    })
    render(
      <MemoryRouter initialEntries={['/squad?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <SquadPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => screen.getByText('Goleiro Teste'))

    const validSelection = [GK, DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
    for (const player of validSelection) {
      fireEvent.click(pickBtn(player.name))
    }
    fireEvent.click(screen.getByRole('button', { name: /salvar escala/i }))

    await waitFor(() =>
      expect(qc.getQueryData(['lineup', 'sg-1', 'club-1'])).toEqual(
        expect.objectContaining({ lineupId: 'l-2' }),
      ),
    )
    // getLineup itself was only called once (the initial mount) — no extra round-trip.
    expect(mockGetLineup).toHaveBeenCalledTimes(1)
  })

  it('writes the saved training result directly into the squad cache without an extra fetch', async () => {
    mockSetTraining.mockResolvedValue(squad)
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    expect(mockGetSquad).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('tab', { name: 'Treino' }))
    fireEvent.click(screen.getByRole('button', { name: /salvar treino/i }))

    await waitFor(() => expect(mockSetTraining).toHaveBeenCalled())
    expect(mockGetSquad).toHaveBeenCalledTimes(1)
  })

  it('does not refetch the squad on a second mount when the query client is reused', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={['/squad?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <SquadPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    expect(mockGetSquad).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    expect(mockGetSquad).toHaveBeenCalledTimes(1)
  })
```

In `frontend/src/features/squad/TacticsPanel.test.tsx`, append inside `describe('TacticsPanel', ...)`:

```ts
  it('invalidates its own tactics query after saving', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    render(
      <MemoryRouter>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <TacticsPanel saveGameId="sg-1" clubId="club-1" starters={[{ id: 'p1', name: 'Craque Um' }]} />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByLabelText('Mentalidade')).toHaveValue('ATTACKING'))
    fireEvent.click(screen.getByRole('button', { name: /salvar tática/i }))

    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['tactics', 'sg-1', 'club-1'] }),
    )
  })
```

In `frontend/src/features/contract/FreeAgentsPage.test.tsx`, append inside `describe('FreeAgentsPage', ...)`:

```ts
  it('invalidates the squad cache after signing so the roster picks up the new player', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ managedClubId: 'club-1', transferWindowOpen: true } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)
    vi.spyOn(api, 'signFreeAgent').mockResolvedValue({} as never)
    const qc = new QueryClient()
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')

    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/free-agents?saveGameId=S1']}>
          <FreeAgentsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    await userEvent.click(await screen.findByRole('button', { name: 'Contratar' }))

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['squad', 'S1', 'club-1'] }))
  })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx src/features/squad/TacticsPanel.test.tsx src/features/contract/FreeAgentsPage.test.tsx`
Expected: FAIL — the lineup write-through assertion finds no cache entry (mutation still only invalidates); the training/squad no-extra-fetch assertions see 2 calls (invalidate still triggers a refetch); the TacticsPanel invalidation spy is never called (no `queryClient` wired into that mutation); the FreeAgentsPage squad-invalidation spy is never called with the squad key.

- [ ] **Step 3: Implement**

In `frontend/src/features/squad/SquadPage.tsx`, add `staleTime: Infinity` to the squad query (around line 184-188):

```ts
  const { data, isLoading, error } = useQuery({
    queryKey: ['squad', saveGameId, clubId],
    queryFn: () => getSquad(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })
```

Add `staleTime: Infinity` to the `savedLineup` query (around line 199-203):

```ts
  const savedLineup = useQuery({
    queryKey: ['lineup', saveGameId, clubId],
    queryFn: () => getLineup(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })
```

Replace the `mutation` (setLineup) definition (lines 232-251):

```ts
  const mutation = useMutation({
    mutationFn: () => {
      const toStarter = (id: string): LineupStarter => {
        const player = players.find(p => p.id === id)!
        return { playerId: id, role: roles[id] ?? DEFAULT_ROLE[player.position] }
      }
      const starters: LineupStarter[] = selected.map(toStarter)
      const benchStarters: LineupStarter[] = bench.filter((id): id is string => id !== null).map(toStarter)
      return setLineup(saveGameId, clubId, { formation, starters, bench: benchStarters }, token ?? '')
    },
    onSuccess: (data) => {
      setSaveSuccess(true)
      // Write-through: the response is already this exact lineup query's fresh data — every
      // observer (this page's own savedLineup query AND Layout's advance-gate query, since both
      // key on the same [saveGameId, clubId]) picks it up immediately, no extra round-trip.
      queryClient.setQueryData(['lineup', saveGameId, clubId], data)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
      // Re-check the advance gate: fixing the lineup may clear an ineligible-starter block.
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
    },
  })
```

Replace the `trainingMutation` (setTraining) definition (lines 253-266):

```ts
  const trainingMutation = useMutation({
    mutationFn: () => {
      const assignments: TrainingAssignment[] = displayPlayers.map(p => ({
        playerId: p.id,
        focus: trainingAssignments[p.id]?.focus ?? null,
        intensity: trainingAssignments[p.id]?.intensity ?? 'NORMAL',
      }))
      return setTraining(saveGameId, clubId, { assignments }, token ?? '')
    },
    onSuccess: (data) => {
      setTrainingSaveSuccess(true)
      // Write-through: setTraining already returns a SquadResponse — the exact shape of the
      // squad query — so patch the cache directly instead of paying for a refetch.
      queryClient.setQueryData(['squad', saveGameId, clubId], data)
    },
  })
```

In `frontend/src/features/squad/TacticsPanel.tsx`, add `useQueryClient` to the import (line 2):

```ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
```

Instantiate it inside the component (after `const { token } = useAuth()` on line 49):

```ts
  const { token } = useAuth()
  const queryClient = useQueryClient()
```

Replace the `mutation` definition (lines 75-91):

```ts
  const mutation = useMutation({
    mutationFn: () =>
      setTactics(
        saveGameId,
        clubId,
        {
          mentality,
          pressing,
          marking,
          penaltyTakerId: penalty || null,
          freekickTakerId: freekick || null,
          cornerTakerId: corner || null,
        },
        token ?? '',
      ),
    onSuccess: () => {
      setSaved(true)
      void queryClient.invalidateQueries({ queryKey: ['tactics', saveGameId, clubId] })
    },
  })
```

In `frontend/src/features/contract/FreeAgentsPage.tsx`, replace the `signMut` definition (lines 47-53):

```ts
  const signMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      signFreeAgent(saveGameId, v.playerId, { wage: v.wage, contractYears: v.contractYears }, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
      // A signed player joins the managed club's roster.
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, save.data?.managedClubId ?? ''] })
    },
  })
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx src/features/squad/TacticsPanel.test.tsx src/features/contract/FreeAgentsPage.test.tsx && npx tsc --noEmit`
Expected: PASS — all tests green (including every pre-existing test in these three files), no type errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/TacticsPanel.tsx frontend/src/features/contract/FreeAgentsPage.tsx frontend/src/features/squad/SquadPage.test.tsx frontend/src/features/squad/TacticsPanel.test.tsx frontend/src/features/contract/FreeAgentsPage.test.tsx
git commit -m "feat(squad): staleTime: Infinity + write-through saves + close tactics/free-agent invalidation gaps"
```

---

## Task 4: Mercado caching (optimistic offers + invalidation gaps)

**Files:**
- Modify: `frontend/src/features/transfer/TransferMarketPage.tsx`
- Modify: `frontend/src/components/Layout.tsx:83-96`
- Test: `frontend/src/features/transfer/TransferMarketPage.test.tsx`
- Test: `frontend/src/components/Layout.test.tsx`

**Interfaces:**
- Consumes: `TransferOffer` type (`frontend/src/api/types.ts:436-452`), `save.data?.managedClubId` (already computed at `TransferMarketPage.tsx:288`).

- [ ] **Step 1: Write the failing tests**

In `frontend/src/features/transfer/TransferMarketPage.test.tsx`, add `withdrawOffer` to the imported names (line 24):

```ts
import { getSaveGame, getMarket, getClubs, getOffers, makeOffer, respondToOffer, withdrawOffer, getFreeAgents, getSquad } from '../../api/career'
```

Append inside `describe('TransferMarketPage', ...)`, after the existing tests:

```ts
  it('optimistically removes a withdrawn offer before the request resolves', async () => {
    const OUTGOING = {
      id: 'out1', playerId: 'p9', playerName: 'Alvo Nove', fromClubId: 'club-1', fromClubName: 'Criciúma',
      toClubId: 'club-9', toClubName: 'Rival FC', direction: 'OUTGOING' as const, amount: 1000000,
      counterAmount: 1300000, status: 'COUNTERED' as const, round: 0, wage: null, contractYears: null,
    }
    vi.mocked(getOffers).mockResolvedValue([OUTGOING])
    let resolveWithdraw!: (v: typeof OUTGOING) => void
    vi.mocked(withdrawOffer).mockReturnValue(new Promise(resolve => { resolveWithdraw = resolve }))

    renderPage()
    expect(await screen.findByText(/contraproposta de/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Desistir' }))

    // Removed immediately — before the mocked request has resolved.
    await waitFor(() => expect(screen.queryByText(/contraproposta de/i)).not.toBeInTheDocument())

    resolveWithdraw({ ...OUTGOING, status: 'WITHDRAWN' })
  })

  it('restores the offer list if withdrawing fails', async () => {
    const OUTGOING = {
      id: 'out1', playerId: 'p9', playerName: 'Alvo Nove', fromClubId: 'club-1', fromClubName: 'Criciúma',
      toClubId: 'club-9', toClubName: 'Rival FC', direction: 'OUTGOING' as const, amount: 1000000,
      counterAmount: 1300000, status: 'COUNTERED' as const, round: 0, wage: null, contractYears: null,
    }
    vi.mocked(getOffers).mockResolvedValue([OUTGOING])
    vi.mocked(withdrawOffer).mockRejectedValue(new Error('network'))

    renderPage()
    expect(await screen.findByText(/contraproposta de/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Desistir' }))
    await waitFor(() => expect(screen.queryByText(/contraproposta de/i)).not.toBeInTheDocument())

    // Rolled back once the rejection settles.
    await waitFor(() => expect(screen.getByText(/contraproposta de/i)).toBeInTheDocument())
  })

  it('optimistically removes an accepted incoming offer and invalidates the squad', async () => {
    vi.mocked(getOffers).mockResolvedValue([
      { id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
        toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
        counterAmount: null, status: 'PENDING', round: 0, wage: null, contractYears: null },
    ])
    let resolveRespond!: (v: unknown) => void
    vi.mocked(respondToOffer).mockReturnValue(new Promise(resolve => { resolveRespond = resolve }))
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')

    render(
      <MemoryRouter initialEntries={['/transfers?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <TransferMarketPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    expect(await screen.findByText('Meu Jogador')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Aceitar' }))
    await waitFor(() => expect(screen.queryByText('Meu Jogador')).not.toBeInTheDocument())

    resolveRespond({
      id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
      toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
      counterAmount: null, status: 'ACCEPTED', round: 0, wage: null, contractYears: null,
    })

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['squad', 'sg-1', 'club-1'] }))
  })

  it('restores a rejected incoming offer if the request fails', async () => {
    vi.mocked(getOffers).mockResolvedValue([
      { id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
        toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
        counterAmount: null, status: 'PENDING', round: 0, wage: null, contractYears: null },
    ])
    vi.mocked(respondToOffer).mockRejectedValue(new Error('network'))

    renderPage()
    expect(await screen.findByText('Meu Jogador')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Recusar' }))
    await waitFor(() => expect(screen.queryByText('Meu Jogador')).not.toBeInTheDocument())

    // Rolled back once the rejection settles.
    await waitFor(() => expect(screen.getByText('Meu Jogador')).toBeInTheDocument())
  })

  it('write-throughs an accepted outgoing offer without re-fetching offers, and invalidates market + squad', async () => {
    vi.mocked(makeOffer).mockResolvedValue({
      id: 'o1', playerId: 'p1', playerName: 'Alvo Um', fromClubId: 'club-1', fromClubName: 'Criciúma',
      toClubId: 'club-9', toClubName: 'Rival FC', direction: 'OUTGOING', amount: 3000000,
      counterAmount: null, status: 'ACCEPTED', round: 0, wage: null, contractYears: null,
    })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    render(
      <MemoryRouter initialEntries={['/transfers?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <TransferMarketPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    openMarketTab()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(getOffers).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: /fazer proposta/i }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/Valor para Alvo Um/i), { target: { value: '3000000' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /fazer proposta/i }))

    await waitFor(() => expect(screen.getByText(/Proposta aceita/i)).toBeInTheDocument())
    expect(getOffers).toHaveBeenCalledTimes(1)
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['market', 'sg-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['squad', 'sg-1', 'club-1'] })
  })

  it('does not refetch the market or offers on a second mount when the query client is reused', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={['/transfers?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <TransferMarketPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(getMarket).toHaveBeenCalledTimes(1)
    expect(getOffers).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(getMarket).toHaveBeenCalledTimes(1)
    expect(getOffers).toHaveBeenCalledTimes(1)
  })
```

In `frontend/src/components/Layout.test.tsx`, append inside `describe('Layout shell', ...)`:

```ts
  it('invalidates the market cache after an instant advance (AI trades can relist/withdraw players)', async () => {
    ;(getAdvancePreview as Mock).mockResolvedValue({ ...PREVIEW_OK, managedClubPlaysNext: false } satisfies AdvancePreviewResponse)
    ;(advanceRound as Mock).mockResolvedValue(ADVANCE_RESPONSE)
    seedAuth('U1', 'sg-1')
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/dashboard']}>
          <AuthProvider>
            <Layout><div>Page content</div></Layout>
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    const avancarBtn = await screen.findByRole('button', { name: /^avançar$/i })
    fireEvent.click(avancarBtn)

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['market'] }))
  })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/transfer/TransferMarketPage.test.tsx src/components/Layout.test.tsx`
Expected: FAIL — withdraw/respond still resolve via plain `invalidateQueries` (no instant removal, so the "removed immediately" assertions time out before the mocked promise ever resolves); the write-through test finds a second `getOffers` call; Layout's advance never invalidates `['market']`.

- [ ] **Step 3: Implement**

In `frontend/src/features/transfer/TransferMarketPage.tsx`, add `staleTime: Infinity` to `market` and `offers` (lines 280-285):

```ts
  const market = useQuery({
    queryKey: ['market', saveGameId, appliedFilters, page],
    queryFn: () => getMarket(saveGameId, appliedFilters, page, MARKET_PAGE_SIZE, token ?? ''),
    enabled,
    staleTime: Infinity,
  })
  const offers = useQuery({
    queryKey: ['offers', saveGameId],
    queryFn: () => getOffers(saveGameId, token ?? ''),
    enabled,
    staleTime: Infinity,
  })
```

Replace the block from `const invalidate = ...` through the `respondMut` declaration (lines 296-327) entirely:

```ts
  // The most recent negotiation outcome (offer / accept-counter / respond), surfaced as the
  // feedback banner. Every action that produces an AI decision feeds this — not just makeOffer —
  // so accepting a counter or answering an incoming bid also tells the user what happened.
  const [lastResult, setLastResult] = useState<TransferOffer | null>(null)

  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; amount: number; wage: number; contractYears: number }) =>
      makeOffer(saveGameId, v, token ?? ''),
    onSuccess: (offer) => {
      // Write-through: the AI's decision is unknown until the response arrives, but once it
      // does, upsert it into the cache instead of paying for an offers refetch.
      queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) => {
        const rest = (old ?? []).filter(o => o.id !== offer.id)
        return [...rest, offer]
      })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      if (offer.status === 'ACCEPTED') {
        void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
        void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
      }
      setLastResult(offer)
      setOfferTarget(null)
    },
  })

  const acceptMut = useMutation({
    mutationFn: (id: string) => acceptCounter(saveGameId, id, token ?? ''),
    onSuccess: (offer) => {
      queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) =>
        (old ?? []).map(o => (o.id === offer.id ? offer : o)))
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      if (offer.status === 'ACCEPTED') {
        void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
        void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
      }
      setLastResult(offer)
    },
  })

  const withdrawMut = useMutation({
    mutationFn: (id: string) => withdrawOffer(saveGameId, id, token ?? ''),
    onMutate: async (id: string) => {
      await queryClient.cancelQueries({ queryKey: ['offers', saveGameId] })
      const previous = queryClient.getQueryData<TransferOffer[]>(['offers', saveGameId])
      queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) =>
        (old ?? []).filter(o => o.id !== id))
      return { previous }
    },
    onError: (_err, _id, context) => {
      if (context?.previous) queryClient.setQueryData(['offers', saveGameId], context.previous)
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
    },
  })

  const [counters, setCounters] = useState<Record<string, string>>({})
  const respondMut = useMutation({
    mutationFn: (v: { offerId: string; action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }) =>
      respondToOffer(saveGameId, v.offerId, { action: v.action, counterAmount: v.counterAmount }, token ?? ''),
    onMutate: async (v) => {
      await queryClient.cancelQueries({ queryKey: ['offers', saveGameId] })
      const previous = queryClient.getQueryData<TransferOffer[]>(['offers', saveGameId])
      // ACCEPT/REJECT are the manager's own explicit, fully-predictable choice — remove the
      // card immediately. COUNTER's outcome depends on the AI's next move, so leave it alone
      // and let onSuccess's write-through handle it once the response arrives.
      if (v.action !== 'COUNTER') {
        queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) =>
          (old ?? []).filter(o => o.id !== v.offerId))
      }
      return { previous }
    },
    onSuccess: (offer, v) => {
      setLastResult(offer)
      if (v.action === 'COUNTER') {
        queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) =>
          (old ?? []).map(o => (o.id === offer.id ? offer : o)))
      }
    },
    onError: (_err, _v, context) => {
      if (context?.previous) queryClient.setQueryData(['offers', saveGameId], context.previous)
    },
    onSettled: (_offer, _err, v) => {
      if (v.action !== 'COUNTER') {
        // Belt-and-suspenders against any server-side divergence (e.g. a race with a round-advance).
        void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
      }
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      if (v.action === 'ACCEPT') {
        void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
      }
    },
  })
```

In `frontend/src/features/transfer/TransferMarketPage.tsx`, add `withdrawOffer` to the import from `'../../api/career'` (line 8-11):

```ts
import {
  getSaveGame, getMarket, getClubs, getOffers, getSquad, getPlayer,
  makeOffer, acceptCounter, withdrawOffer, respondToOffer,
} from '../../api/career'
```

(This import already exists unchanged — no edit needed; listed here only to confirm `withdrawOffer` was already available.)

In `frontend/src/components/Layout.tsx`, add one line to `advanceMutation`'s `onSuccess` (lines 83-96):

```ts
  const advanceMutation = useMutation<AdvanceResponse>({
    mutationFn: () => advanceRound(saveGameId!, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['saveGame', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['news', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['standings'] })
      void queryClient.invalidateQueries({ queryKey: ['finances'] })
      void queryClient.invalidateQueries({ queryKey: ['offers'] })
      void queryClient.invalidateQueries({ queryKey: ['squad'] })
      void queryClient.invalidateQueries({ queryKey: ['market'] })
      // The advance outcome is persisted as a news item; show it on the News tab.
      void navigate(`/news?saveGameId=${saveGameId}`)
    },
  })
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/transfer/TransferMarketPage.test.tsx src/components/Layout.test.tsx && npx tsc --noEmit`
Expected: PASS — all tests green (including every pre-existing test in both files), no type errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/transfer/TransferMarketPage.tsx frontend/src/components/Layout.tsx frontend/src/features/transfer/TransferMarketPage.test.tsx frontend/src/components/Layout.test.tsx
git commit -m "feat(market): optimistic offer withdraw/accept/reject, write-through offer/counter, close market invalidation gap on advance"
```

---

## Task 5: Classificação staleTime

**Files:**
- Modify: `frontend/src/features/competition/TablePage.tsx:97-101`
- Modify: `frontend/src/features/season/SeasonSummaryPage.tsx:36-40`
- Test: `frontend/src/features/competition/TablePage.test.tsx`

**Interfaces:**
- No new triggers: `Layout.tsx`'s `advanceMutation` and `LiveMatchPage.tsx`'s `finishMut` already invalidate `['standings']` (broad) — both untouched by this task.

- [ ] **Step 1: Write the failing test**

Append inside `describe('TablePage', ...)` in `frontend/src/features/competition/TablePage.test.tsx`:

```ts
  it('does not refetch the standings on a second mount when the query client is reused', async () => {
    const qc = makeQueryClient()
    const renderOnce = () => render(
      <MemoryRouter initialEntries={[`/table?saveGameId=sg-1&competitionId=comp-1&managedClubId=${MANAGED_CLUB_ID}`]}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <TablePage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByText('Criciúma')).toBeInTheDocument())
    expect(mockGetStandings).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByText('Criciúma')).toBeInTheDocument())
    expect(mockGetStandings).toHaveBeenCalledTimes(1)
  })
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/competition/TablePage.test.tsx`
Expected: FAIL — `mockGetStandings` is called twice (default `staleTime: 0` refetches on the second mount).

- [ ] **Step 3: Implement**

In `frontend/src/features/competition/TablePage.tsx`, add `staleTime: Infinity` to the standings query (lines 97-101):

```ts
  const { data, isLoading, error } = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
    staleTime: Infinity,
  })
```

In `frontend/src/features/season/SeasonSummaryPage.tsx`, add `staleTime: Infinity` to its own standings query (lines 36-40) — same query key, same reasoning:

```ts
  const standings = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
    staleTime: Infinity,
  })
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/features/competition/TablePage.test.tsx src/features/season/SeasonSummaryPage.test.tsx && npx tsc --noEmit`
Expected: PASS — all tests green, no type errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/competition/TablePage.tsx frontend/src/features/season/SeasonSummaryPage.tsx frontend/src/features/competition/TablePage.test.tsx
git commit -m "feat(standings): staleTime: Infinity — existing advance/finish triggers already cover every round change"
```

---

## Task 6: Rollover invalidation hub + Agentes Livres / Histórico / Base staleTime

**Files:**
- Modify: `frontend/src/features/season/SeasonSummaryPage.tsx`
- Modify: `frontend/src/features/contract/FreeAgentsPage.tsx:45`
- Modify: `frontend/src/features/season/SeasonHistoryPage.tsx:19-29`
- Modify: `frontend/src/features/base/BasePage.tsx:32-36`
- Test: `frontend/src/features/season/SeasonSummaryPage.test.tsx`
- Test: `frontend/src/features/contract/FreeAgentsPage.test.tsx`
- Test: `frontend/src/features/season/SeasonHistoryPage.test.tsx`
- Test: `frontend/src/features/base/BasePage.test.tsx`

**Interfaces:**
- Consumes: `managedClubId` — already read from `useSearchParams()` in `SeasonSummaryPage.tsx:34`.

- [ ] **Step 1: Write the failing tests**

In `frontend/src/features/season/SeasonSummaryPage.tsx`'s test file, add `QueryClient`/`QueryClientProvider` import already present; append inside `describe('SeasonSummaryPage', ...)`:

```ts
  it('invalidates squad, youth, free agents, and season history after rolling over', async () => {
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027, currentRound: 0, retirements: [], expiredContracts: [], youthIntake: [], youthDepartures: [],
    })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    render(
      <MemoryRouter initialEntries={['/season-summary?saveGameId=sg-1&competitionId=c-1&managedClubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <SeasonSummaryPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => screen.getByRole('button', { name: /próxima temporada/i }))
    fireEvent.click(screen.getByRole('button', { name: /próxima temporada/i }))

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['squad', 'sg-1', 'club-1'] }))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['youth', 'sg-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['freeAgents', 'sg-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['seasonHistory', 'sg-1'] })
  })
```

In `frontend/src/features/contract/FreeAgentsPage.test.tsx`, append inside `describe('FreeAgentsPage', ...)`:

```ts
  it('does not refetch the free-agent pool on a second mount when the query client is reused', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ transferWindowOpen: true } as never)
    const pool = vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)
    const qc = new QueryClient()
    const renderOnce = () => render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/free-agents?saveGameId=S1']}>
          <FreeAgentsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    const { unmount } = renderOnce()
    await screen.findByText('Livre Um')
    expect(pool).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await screen.findByText('Livre Um')
    expect(pool).toHaveBeenCalledTimes(1)
  })
```

In `frontend/src/features/season/SeasonHistoryPage.test.tsx`, append inside `describe('SeasonHistoryPage', ...)`:

```ts
  it('does not refetch the season history on a second mount when the query client is reused', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={['/history?saveGameId=sg-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <SeasonHistoryPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByText('Campeao FC')).toBeInTheDocument())
    expect(getSeasonHistory).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByText('Campeao FC')).toBeInTheDocument())
    expect(getSeasonHistory).toHaveBeenCalledTimes(1)
  })
```

In `frontend/src/features/base/BasePage.test.tsx`, append inside `describe('BasePage', ...)`:

```ts
  it('does not refetch the youth list on a second mount when the query client is reused', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={['/base?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <BasePage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByText('Joia da Base')).toBeInTheDocument())
    expect(mockList).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByText('Joia da Base')).toBeInTheDocument())
    expect(mockList).toHaveBeenCalledTimes(1)
  })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/season/SeasonSummaryPage.test.tsx src/features/contract/FreeAgentsPage.test.tsx src/features/season/SeasonHistoryPage.test.tsx src/features/base/BasePage.test.tsx`
Expected: FAIL — rollover's `onSuccess` doesn't exist yet so `invalidateSpy` is never called; the three no-refetch tests each see 2 calls (default `staleTime: 0`).

- [ ] **Step 3: Implement**

In `frontend/src/features/season/SeasonSummaryPage.tsx`, add `useQueryClient` to the import (line 1):

```ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
```

Instantiate it and give `rollover` an `onSuccess` (replace lines 29-50):

```ts
export function SeasonSummaryPage() {
  const { token } = useAuth()
  const isMobile = useIsMobile()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const competitionId = searchParams.get('competitionId') ?? ''
  const managedClubId = searchParams.get('managedClubId') ?? ''

  const standings = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
    staleTime: Infinity,
  })

  const scorers = useQuery({
    queryKey: ['topScorers', saveGameId, competitionId],
    queryFn: () => getTopScorers(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  const rollover = useMutation<RolloverResponse>({
    mutationFn: () => rolloverSeason(saveGameId, token ?? ''),
    onSuccess: () => {
      // Aging, retirements, contract expiry, youth intake/departures, and free-agent pool
      // top-up all happen here — this is the one place that must tell every affected cache
      // it's stale now (under staleTime: Infinity nothing else will).
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
      void queryClient.invalidateQueries({ queryKey: ['youth', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['seasonHistory', saveGameId] })
    },
  })
```

In `frontend/src/features/contract/FreeAgentsPage.tsx`, add `staleTime: Infinity` to the `pool` query (line 45):

```ts
  const pool = useQuery({
    queryKey: ['freeAgents', saveGameId],
    queryFn: () => getFreeAgents(saveGameId, token ?? ''),
    enabled,
    staleTime: Infinity,
  })
```

In `frontend/src/features/season/SeasonHistoryPage.tsx`, add `staleTime: Infinity` to both queries (lines 19-29):

```ts
  const history = useQuery({
    queryKey: ['seasonHistory', saveGameId],
    queryFn: () => getSeasonHistory(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })

  const detail = useQuery({
    queryKey: ['season', saveGameId, selectedYear],
    queryFn: () => getSeason(saveGameId, selectedYear!, token ?? ''),
    enabled: Boolean(saveGameId && token && selectedYear !== null),
    staleTime: Infinity,
  })
```

In `frontend/src/features/base/BasePage.tsx`, add `staleTime: Infinity` to the youth query (lines 32-36):

```ts
  const { data, isLoading, error } = useQuery({
    queryKey: ['youth', saveGameId],
    queryFn: () => listProspects(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/season/SeasonSummaryPage.test.tsx src/features/contract/FreeAgentsPage.test.tsx src/features/season/SeasonHistoryPage.test.tsx src/features/base/BasePage.test.tsx && npx tsc --noEmit`
Expected: PASS — all tests green (including every pre-existing test in these four files), no type errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/season/SeasonSummaryPage.tsx frontend/src/features/contract/FreeAgentsPage.tsx frontend/src/features/season/SeasonHistoryPage.tsx frontend/src/features/base/BasePage.tsx frontend/src/features/season/SeasonSummaryPage.test.tsx frontend/src/features/contract/FreeAgentsPage.test.tsx frontend/src/features/season/SeasonHistoryPage.test.tsx frontend/src/features/base/BasePage.test.tsx
git commit -m "feat(season): rollover becomes a real invalidation hub; staleTime: Infinity for free agents, history, and base"
```

---

## Final verification (after all 6 tasks)

Run the full suites once more on the completed branch:

```bash
cd backend && ./gradlew test
cd frontend && npx tsc --noEmit && npx vitest run
```

Both must be green (aside from the two known pre-existing RNG-sensitive flakes, `AiListingEngineIT` and `TransferServiceSellingIT`, which are unrelated to this branch and should be re-run in isolation to confirm no regression if they fire).

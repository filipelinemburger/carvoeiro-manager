# Campeonato Brasileiro Série C 2026 — field, format and seed impact

**Researched:** 2026-08-13 (mid-season; the 2026 field is fully settled and officially published).
**Purpose:** input for a future seed spec adding Série C 2026 alongside the existing Série A (20), Série B (20) and Catarinense (12).

> `docs/` had no research convention (only `docs/superpowers/plans/`, `docs/superpowers/specs/` and
> `docs/deployment/`), so **`docs/research/` was created for this document.**

## Primary source

The load-bearing source for everything below is the CBF's own **Regulamento Específico da Competição
(REC)** for Série C 2026, signed by the Diretoria de Competições in Rio de Janeiro on **23 February 2026**:

- **REC PDF:** <https://stcbfsiteprdimgbrs.blob.core.windows.net/img-site/cdn/REC_Brasileiro_Serie_C_2026_f894bdfed3.pdf> (accessed 2026-08-13; 18 pages; full text extracted and read)
- CBF announcement of the technical documents: <https://www.cbf.com.br/futebol-brasileiro/noticias/copa-do-brasil/sub-20/cbf-publica-documentos-tecnicos-da-serie-c-de-2026> (accessed 2026-08-13 — **note:** `cbf.com.br` served an untrusted TLS chain to the fetch tool, so this page could not be read directly; its content is corroborated by the REC itself and by secondary reports)
- CBF fixture/table hub: <https://www.cbf.com.br/futebol-brasileiro/tabelas/campeonato-brasileiro/serie-c/2026> (same TLS problem — **not readable**, see Uncertainties)

Secondary, used only for cross-checking and for stadium/capacity data the REC does not carry:

- en.wikipedia, *2026 Campeonato Brasileiro Série C*: <https://en.wikipedia.org/wiki/2026_Campeonato_Brasileiro_S%C3%A9rie_C> (accessed 2026-08-13)
- pt.wikipedia, *Campeonato Brasileiro de Futebol de 2026 – Série C*: <https://pt.wikipedia.org/wiki/Campeonato_Brasileiro_de_Futebol_de_2026_-_S%C3%A9rie_C> (accessed 2026-08-13)
- Arena Correio, "CBF define regulamento da Série C 2026, última edição com 20 equipes" (published 2026-03-05): <https://arenacorreio.com.br/cbf-define-regulamento-da-serie-c-2026-ultima-edicao-com-20-equipes/> (accessed 2026-08-13)

---

## 1. The field — confirmed and complete

**The 2026 field is fully confirmed.** It is not a projection: the 20 clubs are named exhaustively in
**Anexo A – Relação dos Clubes Participantes** and again, with 2025 finishing position, in **Anexo B**
of the REC. Nothing is pending.

Composition, per **Art. 2º** of the REC, follows three admission criteria:

- **Critério 1** — relegated from Série B 2025 (4 clubs): Ferroviária, Amazonas, Volta Redonda, Paysandu.
- **Critério 2** — retained from Série C 2025, i.e. finished 5th–16th (12 clubs): Caxias, Brusque,
  Guarani, Floresta, Confiança, Ypiranga, Maringá, Ituano, Botafogo-PB, Figueirense, Anápolis, Itabaiana.
- **Critério 3** — promoted from Série D 2025 (4 clubs): Barra, Santa Cruz, Inter de Limeira, Maranhão.

### Field table

`shortName` column: existing pool code where the club is already seeded, otherwise a **proposed** code
(marked *prop.*). Capacity column gives the figure recommended for the seed; see §5 for the conflicts.

| # | Club (common name) | shortName | UF | Home stadium (2026) | Capacity | In pool? | 2025 origin |
|---|---|---|---|---|---|---|---|
| 1 | Ferroviária | `FER` *prop.* | SP | Estádio Fonte Luminosa (Araraquara) | 20,000 | new | 17th Série B |
| 2 | Amazonas | `AMA` *prop.* | AM | Estádio Carlos Zamith (Manaus) | 10,000 | new | 18th Série B |
| 3 | Volta Redonda | `VRE` *prop.* | RJ | Estádio Raulino de Oliveira (Volta Redonda) | 20,255 ⚠ | new | 19th Série B |
| 4 | Paysandu | `PAY` *prop.* | PA | Estádio Leônidas Sodré de Castro "Curuzu" (Belém) | 16,200 | new | 20th Série B |
| 5 | Caxias | `CAX` *prop.* | RS | Estádio Centenário (Caxias do Sul) | 22,132 | new | 5th Série C |
| 6 | **Brusque** | `BRU` | SC | Estádio Augusto Bauer (Brusque) | 5,000 | **yes** | 6th Série C |
| 7 | Guarani | `GUA` *prop.* | SP | Estádio Brinco de Ouro da Princesa (Campinas) | 29,130 ⚠ | new | 7th Série C |
| 8 | Floresta | `FLO` *prop.* | CE | Estádio Presidente Vargas (Fortaleza) | 20,262 ⚠ | new | 8th Série C |
| 9 | Confiança | `CFZ` *prop.* ⚠ | SE | Estádio Lourival Baptista "Batistão" (Aracaju) | 15,575 ⚠ | new | 9th Série C |
| 10 | Ypiranga | `YPI` *prop.* | RS | Estádio Colosso da Lagoa (Erechim) | 22,000 | new | 10th Série C |
| 11 | Maringá | `MGA` *prop.* ⚠ | PR | Estádio Willie Davids (Maringá) | 16,226 | new | 11th Série C |
| 12 | Ituano | `ITU` *prop.* | SP | Estádio Novelli Júnior (Itu) | 18,560 ⚠ | new | 12th Série C |
| 13 | Botafogo-PB | `BPB` *prop.* ⚠ | PB | Estádio Almeidão (João Pessoa) | 25,770 ⚠ | new | 13th Série C |
| 14 | **Figueirense** | `FIG` | SC | Estádio Orlando Scarpelli (Florianópolis) | 19,584 | **yes** | 14th Série C |
| 15 | Anápolis | `ANA` *prop.* | GO | Estádio Jonas Duarte (Anápolis) | 12,875 ⚠ | new | 15th Série C |
| 16 | Itabaiana | `ITB` *prop.* | SE | Estádio Etelvino Mendonça (Itabaiana) | 12,000 ⚠ | new | 16th Série C |
| 17 | **Barra** | `BAR` | SC | Arena Barra FC | 5,500 ⚠ | **yes** | 1st Série D |
| 18 | Santa Cruz | `SCR` *prop.* ⚠ | PE | Estádio José do Rego Maciel "Arruda" (Recife) | 60,044 | new | 2nd Série D |
| 19 | Inter de Limeira | `ILI` *prop.* ⚠ | SP | Estádio Major José Levy Sobrinho "Limeirão" (Limeira) | 23,475 ⚠ | new | 3rd Série D |
| 20 | Maranhão | `MRN` *prop.* ⚠ | MA | Estádio Governador João Castelo "Castelão" (São Luís) | 40,149 | new | 4th Série D |

⚠ = a conflict or an uncertainty documented in §5 (capacity) or §4 (shortName).

**Santa Catarina representation: 3 clubs — Brusque, Figueirense, Barra — all three already in the pool.**
The other 10 SC clubs in the pool (Avaí, Camboriú, Carlos Renaux, Chapecoense, Concórdia, Criciúma,
Hercílio Luz, Joinville, Marcílio Dias, Santa Catarina) are **not** in Série C 2026.

**17 of 20 clubs are new to the pool.** Adding them takes the pool from 50 to 67 clubs.

---

## 2. The exact 2026 regulation and format

All of the following is stated verbatim in the REC; article numbers are cited.

**Size and shape (Art. 2º, Art. 12).** 20 clubs, three phases:

- **1ª Fase:** 20 clubs in a single group (Grupo A).
- **2ª Fase:** 8 clubs in **two groups of four** (Grupos B and C) — the *quadrangulares*.
- **3ª Fase (Final):** 2 clubs (Grupo D).

**Points are reset to zero at the start of each phase** (Art. 12, parágrafo único). This is the single
most important rule for a competition-format engine: the second phase is not a continuation of the
first-phase table.

**1ª Fase (Art. 14).** Pontos corridos, **single round-robin (turno único)**, every club plays every
other exactly once, over **19 rounds**. Because 19 is odd, home/away counts are asymmetric and are
allocated by 2025 pedigree rather than drawn:

- **10 home / 9 away** — the 4 clubs relegated from Série B 2025, plus the 6 clubs that finished
  5th–10th in Série C 2025 (Art. 14 §1º).
- **9 home / 10 away** — the 6 clubs that finished 11th–16th in Série C 2025, plus the 4 clubs
  promoted from Série D 2025 (Art. 14 §2º).

Anexo B tabulates this club by club; the split matches the "2025 origin" column in the field table above
(rows 1–10 get 10 home matches, rows 11–20 get 9).

**Qualification (Art. 15).** The **top 8 on points** at the end of the 1ª Fase advance to the 2ª Fase.
The other 12 are finished, and take final placings 9th–20th in their 1ª Fase order.

**1ª Fase tiebreakers (Art. 16), in order:** most wins → goal difference → goals for → fewest red cards
→ fewest yellow cards → draw of lots. **There is no head-to-head criterion in the first phase.**

**2ª Fase — the quadrangulares (Art. 17, Anexo B).** Two groups of four, **double round-robin
(turno e returno)** within each group, **6 rounds** — 3 home and 3 away matches per club. Group
composition is seeded by 1ª Fase finishing position:

- **Grupo B:** 1st, 4th, 5th, 8th of the 1ª Fase.
- **Grupo C:** 2nd, 3rd, 6th, 7th of the 1ª Fase.

**2ª Fase tiebreakers (Art. 20), in order:** most wins → goal difference → goals for → **head-to-head**
→ fewest red cards → fewest yellow cards → draw of lots. Head-to-head is the aggregate of the two
matches, "o resultado do jogo de 180 minutos" (§1º), and is **skipped entirely when more than two clubs
are level** (§2º).

**3ª Fase — the final (Art. 19, 21, 22, 24).** The **winner of Grupo B plays the winner of Grupo C**
over **two legs**. Tiebreak is goal difference across the two legs, then a **penalty shootout** starting
within 10 minutes of the end of the second leg (Art. 21, parágrafo único). **No away-goals rule and no
extra time.** Second-leg home advantage goes to the club with more points across the whole competition
(all phases summed), then more wins, then better goal difference, then a draw (Art. 22); the CBF retains
the right to designate the actual venue (Art. 23).

**Final placings 3rd–8th (Art. 25).** 3rd and 4th are the two group runners-up, ordered by best overall
campaign (1ª + 2ª Fase summed). 5th–8th are the remaining 2ª Fase clubs, also ordered by summed campaign.
The "melhor campanha" ladder is: points → wins → goal difference → goals for → fewest reds → fewest
yellows → draw.

**Other rules of note.** Yellow cards are wiped at the end of the 1ª Fase, but a pending suspension from
a third yellow or a red still stands (Art. 40). The final round of the 1ª Fase and of each 2ª Fase group
must kick off simultaneously, except for matches with nothing at stake (Art. 41). Stadiums must seat at
least **4,000** with adequate floodlighting; temporary stands do not count (Art. 31). Squad registration
is capped at 50 players, with a window to swap up to 8 (Art. 7º §1º).

**Calendar.** 5 April – 25 October 2026, 27 matchdays, 216 matches
([Arena Correio, 2026-03-05](https://arenacorreio.com.br/cbf-define-regulamento-da-serie-c-2026-ultima-edicao-com-20-equipes/)).
See §5 for a start-date conflict.

---

## 3. Promotion and relegation

- **Promotion: 4 clubs.** "Os 2 (dois) Clubes primeiros colocados na classificação final de cada grupo
  (B e C) da 2ª fase … ascenderão ao Campeonato Brasileiro Série B …, totalizando 4 (quatro) Clubes"
  (**Art. 5º**). So promotion is decided in the quadrangulares, **not** in the final — both finalists
  and both runners-up go up, and the final only decides the title.
- **Relegation: 2 clubs.** "Os 2 (dois) Clubes últimos colocados na classificação final da 1ª Fase …
  descenderão para o Campeonato Brasileiro Série D 2027" (**Art. 42**). Relegation is settled at the end
  of the **first phase**, on 19th and 20th place.
- **Champion's bonus.** The Série C champion enters the **3rd phase of the Copa do Brasil 2027** directly
  (Art. 6º).

**Note the asymmetry: 4 up, only 2 down.** This is deliberate — 2026 is the last 20-club edition; Série C
expands to **24 clubs in 2027** and **28 in 2028**
([Arena Correio](https://arenacorreio.com.br/cbf-define-regulamento-da-serie-c-2026-ultima-edicao-com-20-equipes/),
2026-03-05). If the seed ever simulates a 2027 rollover, the 20 → 24 arithmetic is
`20 − 4 promoted − 2 relegated + 4 from Série B + 6 from Série D = 24` (the Série D intake is an
inference, not something the Série C REC states — see §5).

---

## 4. Seed impact: pool membership and shortName collisions

**Already in the pool (3):** Brusque `BRU`, Figueirense `FIG`, Barra `BAR`. All three have correct
stadium data already, with two caveats flagged in §5 (Barra's capacity and legal name).

**New clubs (17).** Below is everything `clubs.json` requires per club except the tactical fields
(`formation`, `mentality`, `pressing`, `marking`), colours and `players`, which are game-design choices
rather than research output. Legal names are transcribed from **REC Anexo A**; nicknames are from
pt.wikipedia infobox `alcunha(s)` fields (all 13 checked were explicit — none guessed).

| commonName | Legal `name` (REC Anexo A) | `nickname` | `city` | `state` | `stadium` | `stadiumCapacity` |
|---|---|---|---|---|---|---|
| Ferroviária | Associação Ferroviária de Esportes (REC: "Ferroviária SAF") | Locomotiva | Araraquara | SP | Estádio Fonte Luminosa | 20,000 |
| Amazonas | Amazonas FC – Sociedade Anônima de Futebol | Onça-Pintada | Manaus | AM | Estádio Carlos Zamith | 10,000 |
| Volta Redonda | Volta Redonda Futebol Clube | Voltaço | Volta Redonda | RJ | Estádio Raulino de Oliveira | 20,255 ⚠ |
| Paysandu | Paysandu Sport Club | Papão | Belém | PA | Estádio Leônidas Sodré de Castro | 16,200 |
| Caxias | Sociedade Esportiva e Recreativa Caxias do Sul | Grená | Caxias do Sul | RS | Estádio Centenário | 22,132 |
| Guarani | Guarani Futebol Clube | Bugre | Campinas | SP | Estádio Brinco de Ouro da Princesa | 29,130 ⚠ |
| Floresta | Floresta Esporte Clube | Verdão da Vila | Fortaleza | CE | Estádio Presidente Vargas | 20,262 ⚠ |
| Confiança | Associação Desportiva Confiança | Dragão | Aracaju | SE | Estádio Lourival Baptista | 15,575 ⚠ |
| Ypiranga | Ypiranga Futebol Clube | Canarinho | Erechim | RS | Estádio Colosso da Lagoa | 22,000 |
| Maringá | Maringá Futebol Clube S.A.F. | Dogão | Maringá | PR | Estádio Willie Davids | 16,226 |
| Ituano | Ituano Futebol Clube | Galo de Itu | Itu | SP | Estádio Novelli Júnior | 18,560 ⚠ |
| Botafogo-PB | Botafogo Futebol Clube | Belo | João Pessoa | PB | Estádio Almeidão | 25,770 ⚠ |
| Anápolis | Anápolis Futebol Clube | Tricolor da Boa Vista | Anápolis | GO | Estádio Jonas Duarte | 12,875 ⚠ |
| Itabaiana | Associação Olímpica de Itabaiana | Tremendão | Itabaiana | SE | Estádio Etelvino Mendonça | 12,000 ⚠ |
| Santa Cruz | Santa Cruz Futebol Clube | Tricolor do Arruda | Recife | PE | Estádio José do Rego Maciel | 60,044 |
| Inter de Limeira | Associação Atlética Internacional | Leão da Paulista | Limeira | SP | Estádio Major José Levy Sobrinho | 23,475 ⚠ |
| Maranhão | Maranhão Atlético Clube | Bode Gregório | São Luís | MA | Estádio Governador João Castelo | 40,149 |

### shortName collisions — 6 of the 17

The obvious 3-letter code is **already taken** for six new clubs. Proposed alternatives are in the field
table in §1.

| New club | Obvious code | Collides with (existing) | Proposed |
|---|---|---|---|
| Confiança | `CON` | **Concórdia** `CON` (SC) | `CFZ` |
| Maringá | `MAR` | **Marcílio Dias** `MAR` (SC) | `MGA` (the city's IATA/plate code, widely used) |
| Botafogo-PB | `BOT` | **Botafogo-SP** `BOT` (and `BFR` = Botafogo-RJ) | `BPB` |
| Santa Cruz | `SAN` / `SCA` | **Santos** `SAN`, **Santa Catarina** `SCA` | `SCR` |
| Inter de Limeira | `INT` | **Internacional** `INT` (RS) | `ILI` |
| Maranhão | `MAR` | **Marcílio Dias** `MAR` (SC) | `MRN` |

Codes verified free against all 50 existing shortNames: `FER`, `AMA`, `VRE`, `PAY`, `CAX`, `GUA`, `FLO`,
`YPI`, `ITU`, `ANA`, `ITB`, plus the six alternatives above. (`VOL` is also free if `VRE` is disliked;
`ITA` is free for Itabaiana but sits one letter from `ITU`, so `ITB` is safer for a human reading a table.)

**Non-collisions worth not panicking about:**

- `FLO` (Floresta) is distinct from `FLA` (Flamengo) and `FLU` (Fluminense).
- `CAX` (Caxias) is distinct from `CAM` (Camboriú) and `CAR` (Carlos Renaux).
- Botafogo-SP's stadium in the pool is literally named **"Estádio Santa Cruz"**, and a club named
  Santa Cruz now joins. That is a naming coincidence, not a data collision, but it will look like a bug
  in any UI that renders stadium names next to club names.

### Other seed-model gaps this introduces

- **`climateZone` has no value for AM or MA.** The enum
  (`backend/src/main/java/br/com/carvoeiromanager/club/ClimateZone.java`) only has `SUL`, `SUDESTE`,
  `CENTRO_OESTE`, `NORDESTE_LITORAL`. Série C 2026 brings in **AM** (Manaus) and **MA** (São Luís), and
  also **PB** and **SE** for the first time. PB and SE map cleanly to `NORDESTE_LITORAL`. AM and MA do
  not: the existing pool already stretches `NORDESTE_LITORAL` to cover **PA** (Remo, Belém), so following
  that precedent for MA and AM is consistent — but Manaus is equatorial-inland, not coastal-northeastern,
  and the weather/attendance model added in `2026-07-30-weather-and-stadium-attendance` will produce
  wrong-feeling results for it. **This is a spec decision, not a research finding — flagging it, not
  resolving it.**
- **Stadium sharing.** Floresta's home ground, Estádio Presidente Vargas, is already in the pool as
  Ceará's stadium (`CEA`, capacity 20,000 there vs 20,262 here). Whether the seed tolerates two clubs
  pointing at the same stadium string, and which capacity wins, needs a decision. (The pool already does
  this: Flamengo and Fluminense both use Maracanã; Brusque and Carlos Renaux both use Augusto Bauer.)
- **"Castelão" is ambiguous.** Maranhão's home is *Estádio Governador João Castelo* in São Luís, a
  completely different ground from Fortaleza's *Arena Castelão* (already in the pool at 63,903). Use full
  names, not the nickname.

---

## 5. Uncertainties and conflicts

Ordered roughly by how much they could bite.

### 5.1 Stadium capacities are the weakest data in this document

**The CBF REC does not publish stadium names or capacities at all** — venues come from each club's
*Termo de Indicação de Estádio* (Art. 3º), which is not public. Every capacity figure here is therefore
**secondary-sourced**, and the two Wikipedias disagree on most of them. The table below shows the spread.
I have taken the higher/official-registered figure where the two describe the same ground, since the seed
uses capacity for attendance modelling rather than for ticketing.

| Club | en.wikipedia | pt.wikipedia | Taken | Comment |
|---|---|---|---|---|
| Volta Redonda | 20,255 | 18,230 | 20,255 | same ground, differing figures |
| Guarani | 29,130 | 20,580 | 29,130 | large gap; Brinco de Ouro's registered vs. certified capacity likely differ |
| Confiança | 15,575 | 15,000 | 15,575 | minor |
| Ituano | 18,560 | 15,600 | 18,560 | |
| Botafogo-PB | 25,770 | 19,000 | 25,770 | Almeidão's post-renovation certified capacity is materially lower than nominal |
| Anápolis | 14,000 | 12,875 | 12,875 | pt figure is the more precise-looking one; **low confidence** |
| Itabaiana | 12,000 | 10,000 | 12,000 | **low confidence** |
| Inter de Limeira | 23,475 | 18,000 | 23,475 | same ground ("Limeirão" = Major José Levy Sobrinho) |
| Floresta | 20,262 | 10,500 | 20,262 | **different stadiums**, see 5.2 |
| Barra | 5,500 | 5,500 | 5,500 | **conflicts with the pool's existing 3,000**, see 5.4 |

Capacities I consider solid (both sources agree, or a dedicated stadium page confirms): Caxias 22,132;
Ypiranga 22,000; Maringá 16,226; Figueirense 19,584; Brusque 5,000; Santa Cruz 60,044; Maranhão 40,149;
Ferroviária 20,000; Amazonas 10,000 (Carlos Zamith).

### 5.2 Three clubs where the two Wikipedias name *different stadiums*

Resolved by checking actual 2026 match reports; the resolution is stated, but note it rests on secondary
reporting, not on the CBF's stadium register.

- **Amazonas** — en.wikipedia says Arena da Amazônia (44,000), pt.wikipedia says Carlos Zamith (10,000).
  **Both are used.** Carlos Zamith is the regular 2026 Série C home ground (multiple 2026 round reports:
  vs Maranhão, vs Caxias, vs Ituano); Arena da Amazônia is reserved for big fixtures. The Amazonas state
  government made Arena da Amazônia, Carlos Zamith and Ismael Benigno available for 2026. **Seed
  recommendation: Carlos Zamith, 10,000** — but this understates Amazonas' real drawing power.
- **Paysandu** — en.wikipedia says Mangueirão (53,635), pt.wikipedia says Curuzu (16,200). **Curuzu is
  the 2026 home ground**: the club president confirmed the majority of 2026 matches at Curuzu with
  Mangueirão as fallback, and 2026 Série C attendance records were set at Curuzu. **Seed recommendation:
  Curuzu, 16,200** — same caveat as Amazonas; big matches move to Mangueirão.
- **Floresta** — en.wikipedia says Presidente Vargas (Fortaleza, 20,262), pt.wikipedia says Domingão
  (Horizonte, 10,500). **Presidente Vargas is where Floresta has hosted in 2026** (round reports vs
  Maringá, vs Confiança, vs Figueirense, vs Barra). The pt.wikipedia entry appears stale. **Seed
  recommendation: Presidente Vargas.**

### 5.3 An internal contradiction inside the CBF REC itself

**Art. 5º says clubs are promoted to "Campeonato Brasileiro Série B 2026".** That is impossible — Série B
2026 is running concurrently. **Art. 25 repeatedly refers to "Clubes que não ascenderem ao Campeonato
Brasileiro Série B 2027"**, and Art. 42 relegates to "Série D 2027". **Art. 5º is a typo for 2027.** The
count (4 clubs) is not in doubt; only the year in that one article is wrong.

**Also in the REC:** Anexo A lists *"Guarani Futebol Clube — SP"*, but the Anexo B Grupo A table gives
Guarani's UF as **SC**. Guarani is from Campinas, São Paulo. **Anexo B's UF cell is a typo; SP is correct.**

### 5.4 Existing pool data for Barra looks wrong

Two problems with the seeded `BAR` entry, both inherited from the Catarinense seed:

- **`name` is `"Associação Desportiva Barra"`.** REC Anexo A calls it **"Barra Futebol Clube Ltda"**, and
  pt.wikipedia titles the article *Barra Futebol Clube (Santa Catarina)*. The seeded legal name appears to
  be **incorrect** and should probably become "Barra Futebol Clube".
- **`stadiumCapacity` is `3000`.** Arena Barra FC opened 19 January 2025 with a stated capacity of
  **5,500** (5,000 covered home seats + 500 away). Both Wikipedias say 5,500. The seeded 3,000 looks
  **stale or wrong.**
- **Minor:** the seed has `city: "Balneário Camboriú"` (where the club is based, founded 18 Jan 2013),
  while en.wikipedia places **Arena Barra FC in Itajaí**. Sources genuinely differ on which municipality
  the ground sits in. The seeded city is defensible; noting it only so nobody "fixes" it in the wrong
  direction.

I have **not** changed any of these — this document only reports them.

### 5.5 Things I could not confirm from a primary source

- **The CBF website was unreachable.** Both <https://www.cbf.com.br/futebol-brasileiro/tabelas/campeonato-brasileiro/serie-c/2026>
  and the CBF news article returned `unable to verify the first certificate` to the fetch tool on
  2026-08-13. This means **the official per-match venue list was never read**, and stadium assignments
  above rest on Wikipedia plus 2026 match reports. Anyone re-doing this with a working browser should
  check that page first — it is the authoritative venue source.
- **Start date: 4 vs 5 April 2026.** en.wikipedia says the competition "began on 4 April"; Arena Correio
  (reporting the CBF's *Tabela Básica*) says 5 April. The CBF news summary retrieved via search also said
  5 April. Both agree the season ends **25 October 2026**. Unresolved; likely a matchday spanning both days.
- **Série C 2027's 24-club composition.** Only the expansion *target* (24 in 2027, 28 in 2028) is
  sourced, from Arena Correio quoting the CBF. How the extra places are filled — my arithmetic implies a
  6-club Série D intake — is **inference, not sourced**. Do not build a 2027 rollover on it.
- **Current 2026 standings.** Deliberately not researched. It is mid-season (round ~16 of 19 as of
  2026-08-13); the table is a moving target and irrelevant to a seed that models the competition
  structure and its starting field.
- **Colours (`primaryColor` / `secondaryColor`)** for the 17 new clubs were not researched — out of
  scope for this brief and trivially available from club crests when the spec is written.

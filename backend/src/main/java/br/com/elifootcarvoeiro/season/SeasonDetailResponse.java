package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.competition.TopScorerResponse;

import java.util.List;

public record SeasonDetailResponse(
        int seasonYear,
        String competitionName,
        String championClubName,
        List<SeasonStandingResponse> table,
        List<TopScorerResponse> topScorers
) {}

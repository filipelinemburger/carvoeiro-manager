package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.competition.TopScorerResponse;

import java.util.List;

public record SeasonDetailResponse(
        int seasonYear,
        String competitionName,
        String championClubName,
        List<SeasonStandingResponse> table,
        List<TopScorerResponse> topScorers
) {}

package com.iflytek.skillhub.dto;

import java.util.List;

public record TicketAnalyzeSuggestionResponse(
        String summary,
        String suggestedTitle,
        String mode,
        String namespace,
        Integer amount,
        List<String> completenessIssues,
        String rationalityAssessment,
        String estimatedComplexity,
        String estimatedEffort,
        String suggestedRewardRange,
        List<String> similarSkills,
        List<String> developmentOutline,
        List<String> acceptanceCriteria,
        List<String> riskPoints,
        List<String> clarificationQuestions
) {}

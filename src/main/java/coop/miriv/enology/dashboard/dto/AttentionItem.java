package coop.miriv.enology.dashboard.dto;

public record AttentionItem(String id, String containerCode, String priority, String priorityLabel,
                            String category, String lotCode, String title, String value, String valueUnit,
                            String detail, String meta, String actionLabel) {}

package com.example.ffmpeg.dto;

import lombok.Data;
import java.util.List;

@Data
public class TrackingResult {
    private int frameCount;
    private int activeTrackers;
    private int totalTrackers;
    private int apiCallsUsed;
    private int apiCallsMax;
    private int dedupOperations;
    private int dedupRemoved;
    private List<TrackerInfo> trackers;
    private String outputPath;
}

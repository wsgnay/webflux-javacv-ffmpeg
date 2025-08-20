package com.example.ffmpeg.dto;

import lombok.Data;

@Data
public class PersonDetection {
    private double[] bbox; // [x1, y1, x2, y2]
    private double confidence;
    private String description;
    private int id;

    public PersonDetection() {}

    public PersonDetection(double[] bbox, double confidence, String description) {
        this.bbox = bbox;
        this.confidence = confidence;
        this.description = description;
    }
}

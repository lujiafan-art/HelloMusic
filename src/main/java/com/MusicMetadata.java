package com;

public class MusicMetadata {
    private String title = "Unknown Title";
    private String artist = "Unknown Artist";
    private String album = "Unknown Album";
    private String genre = "Unknown Genre";
    private int trackNumber;
    private int year;
    private int duration;
    private int bitrate;
    private String comment = "";

    public String getTitle() { return title; }
    public void setTitle(String title) {
        this.title = title != null && !title.isEmpty() ? title : "Unknown Title";
    }

    public String getArtist() { return artist; }
    public void setArtist(String artist) {
        this.artist = artist != null && !artist.isEmpty() ? artist : "Unknown Artist";
    }

    public String getAlbum() { return album; }
    public void setAlbum(String album) {
        this.album = album != null && !album.isEmpty() ? album : "Unknown Album";
    }

    public String getGenre() { return genre; }
    public void setGenre(String genre) {
        this.genre = genre != null && !genre.isEmpty() ? genre : "Unknown Genre";
    }

    public int getTrackNumber() { return trackNumber; }
    public void setTrackNumber(int trackNumber) { this.trackNumber = trackNumber; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }

    public int getBitrate() { return bitrate; }
    public void setBitrate(int bitrate) { this.bitrate = bitrate; }

    public String getComment() { return comment; }
    public void setComment(String comment) {
        this.comment = comment != null ? comment : "";
    }
}
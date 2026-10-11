/*
 * HelloMusic - Local music server with UDP broadcast and REST API
 * Copyright (C) 2026 Lu Jiafan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package core;

@SuppressWarnings({"unused", "WeakerAccess"})
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
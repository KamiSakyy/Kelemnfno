package ru.kelemnfno.anime.data.model;

/** Качество дорожки. */
public class Quality implements java.io.Serializable {

    public final int height;
    public final String label;

    public Quality(int height, String label) {
        this.height = height;
        this.label = label;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Quality && ((Quality) o).height == height;
    }

    @Override
    public int hashCode() {
        return height;
    }
}

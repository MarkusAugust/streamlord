package io.github.markusaugust.streamlord.json.jackson2;

import java.util.List;

/** A Java bean that Jackson fills through its setter rather than a creator. */
public class JavaBean {
    private List<Integer> xs;

    public List<Integer> getXs() {
        return xs;
    }

    public void setXs(List<Integer> xs) {
        this.xs = xs;
    }
}

package nl.inl.blacklab.server.jobs;

public class WindowSettings {

    private int first;

    private int size;

    public WindowSettings(int first, int size) {
        super();
        this.first = first;
        this.size = size;
    }

    public int first() {
        return first;
    }

    public int size() {
        return size;
    }

    @Override
    public String toString() {
        return "firstresult=" + first + ", numofresults=" + size;
    }

}

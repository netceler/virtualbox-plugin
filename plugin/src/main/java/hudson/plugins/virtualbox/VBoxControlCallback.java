package hudson.plugins.virtualbox;

public interface VBoxControlCallback<T> {

    T doWithVboxControl(VirtualBoxControl vboxControl);
}

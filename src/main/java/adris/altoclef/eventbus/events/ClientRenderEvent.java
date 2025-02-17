package adris.altoclef.eventbus.events;

import adris.altoclef.multiversion.DrawContextHelper;

public class ClientRenderEvent {
    public DrawContextHelper context;
    public float tickDelta;

    public ClientRenderEvent(DrawContextHelper context, float tickDelta) {
        this.context = context;
        this.tickDelta = tickDelta;
    }
}

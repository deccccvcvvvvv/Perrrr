#pragma once
#include <EGL/egl.h>
struct mock_state {
    int surfacesCreated, liveSurfaces, contextsCreated, swaps, swapInterval, geomW, geomH;
    int ctxMajor, ctxMinor, ctxProfile; EGLContext lastShare, curContext; EGLSurface curSurface;
    int cursorModeCalls, cursorGrabbed, cursorPosCalls, readyW, readyH; char clipboard[256];
};
extern struct mock_state mock;

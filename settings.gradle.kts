rootProject.name = "DontDisconnectMe"

// The Velocity plugin is the root project; the Paper/Folia companion that
// restores entity ids lives alongside it.
include("backend")
project(":backend").name = "DontDisconnectMe-Backend"

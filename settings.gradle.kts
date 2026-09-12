rootProject.name = "odexa"

include(":libraries:runtime")
listOf("gateway", "catalog", "inventory", "order", "payment", "payment-simulator").forEach {
    include(":services:$it")
}

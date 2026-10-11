package com.example.serverdashboard.data.model

import com.google.gson.annotations.SerializedName

enum class CardSpan {
    FULL,
    HALF
}

enum class CardId(
    val displayName: String,
    val defaultSpan: CardSpan
) {
    @SerializedName("HERO")
    HERO("Health Hero", CardSpan.FULL),

    @SerializedName("CPU")
    CPU("CPU", CardSpan.HALF),

    @SerializedName("MEMORY")
    MEMORY("Memory", CardSpan.HALF),

    @SerializedName("STORAGE")
    STORAGE("Storage", CardSpan.FULL),

    @SerializedName("THERMALS")
    THERMALS("Thermals", CardSpan.HALF),

    @SerializedName("NETWORK")
    NETWORK("Network", CardSpan.HALF),

    @SerializedName("SERVICES")
    SERVICES("Services", CardSpan.HALF),

    @SerializedName("INCIDENTS")
    INCIDENTS("Alerts Summary", CardSpan.HALF),

    @SerializedName("DISK_IO")
    DISK_IO("Disk I/O", CardSpan.FULL),

    @SerializedName("PORTS")
    PORTS("Listening Ports", CardSpan.FULL),

    @SerializedName("CONTAINERS")
    CONTAINERS("Docker Containers", CardSpan.FULL);

    companion object {
        fun defaultOrder(): List<CardId> = listOf(
            HERO,
            CPU,
            MEMORY,
            STORAGE,
            THERMALS,
            NETWORK,
            SERVICES,
            INCIDENTS
        )

        fun defaultHidden(): Set<CardId> = setOf(
            DISK_IO,
            PORTS,
            CONTAINERS
        )

        fun fromString(name: String?): CardId? {
            if (name == null) return null
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}

data class OverviewConfig(
    val cardOrder: List<CardId> = CardId.defaultOrder(),
    val hiddenCards: Set<CardId> = CardId.defaultHidden(),
    val selectedMountPoint: String? = null,
    val version: Int = 1
) {
    /**
     * Returns the ordered list of currently visible cards.
     * Guaranteed to never be empty.
     */
    fun getVisibleCards(): List<CardId> {
        val all = CardId.values().toList()
        val visible = cardOrder.filter { it in all && it !in hiddenCards }
        return visible.ifEmpty { CardId.defaultOrder() }
    }

    /**
     * Validates config: ensures no duplicate cards and unknown cards are cleaned up,
     * while newly added cards in future versions are appended to available list.
     */
    fun validated(): OverviewConfig {
        val all = CardId.values().toList()
        val cleanedOrder = mutableListOf<CardId>()
        for (c in cardOrder) {
            if (c in all && c !in cleanedOrder) {
                cleanedOrder.add(c)
            }
        }
        // Append any known cards not in cardOrder or hiddenCards
        for (c in all) {
            if (c !in cleanedOrder && c !in hiddenCards) {
                cleanedOrder.add(c)
            }
        }
        val cleanedHidden = hiddenCards.filter { it in all }.toSet()
        return copy(cardOrder = cleanedOrder, hiddenCards = cleanedHidden)
    }
}

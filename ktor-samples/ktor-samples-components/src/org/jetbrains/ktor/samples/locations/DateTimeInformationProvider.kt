package org.jetbrains.ktor.samples.locations

import org.jetbrains.ktor.components.*
import java.time.*

@Component
public class DateTimeInformationProvider : InformationProvider {
    override fun information(): Information {
        return Information("DateTime", LocalDateTime.now().toString())
    }
}
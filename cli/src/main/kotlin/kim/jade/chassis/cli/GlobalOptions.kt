package kim.jade.chassis.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import kim.jade.chassis.Application

abstract class GlobalOptions : OptionGroup() {
    open fun prepare() {}
    open fun apply(application: Application) {}
}

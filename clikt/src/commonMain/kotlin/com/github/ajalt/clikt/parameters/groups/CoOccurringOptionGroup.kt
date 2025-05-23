package com.github.ajalt.clikt.parameters.groups

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.parameters.internal.NullableLateinit
import com.github.ajalt.clikt.parameters.options.Option
import com.github.ajalt.clikt.parameters.options.hasEnvvarOrSourcedValue
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parsers.OptionInvocation
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

typealias CoOccurringOptionGroupTransform<GroupT, OutT> = (occurred: Boolean?, group: GroupT, context: Context) -> OutT

class CoOccurringOptionGroup<GroupT : OptionGroup, OutT> internal constructor(
    internal val group: GroupT,
    private val transform: CoOccurringOptionGroupTransform<GroupT, OutT>,
) : ParameterGroupDelegate<OutT> {
    init {
        require(group.options.any { HelpFormatter.Tags.REQUIRED in it.helpTags }) {
            "At least one option in a co-occurring group must use `required()`"
        }
        require(group.options.none { it.eager }) {
            "eager options are not allowed in choice and switch option groups"
        }
    }

    override val groupName: String? get() = group.groupName
    override val groupHelp: String? get() = group.groupHelp
    private var value: OutT by NullableLateinit("Cannot read from option delegate before parsing command line")
    private var occurred = false

    override fun provideDelegate(
        thisRef: BaseCliktCommand<*>,
        property: KProperty<*>,
    ): ReadOnlyProperty<BaseCliktCommand<*>, OutT> {
        thisRef.registerOptionGroup(this)
        for (option in group.options) {

            option.parameterGroup = this
            option.groupName = groupName
            thisRef.registerOption(option)
        }
        return this
    }

    override fun getValue(thisRef: BaseCliktCommand<*>, property: KProperty<*>): OutT = value

    override fun finalize(
        context: Context,
        invocationsByOption: Map<Option, List<OptionInvocation>>,
    ) {
        occurred = invocationsByOption.isNotEmpty() || group.options.any {
            it.hasEnvvarOrSourcedValue(context, invocationsByOption[it] ?: emptyList())
        }
        if (occurred) group.finalize(context, invocationsByOption)
        value = transform(occurred, group, context)
    }

    override fun postValidate(context: Context) {
        if (occurred) group.postValidate(context)
    }

    fun <T> copy(transform: CoOccurringOptionGroupTransform<GroupT, T>): CoOccurringOptionGroup<GroupT, T> {
        return CoOccurringOptionGroup(group, transform)
    }
}

/**
 * Make this group a co-occurring group.
 *
 * The group becomes nullable. At least one option in the group must be [required]. If none of the
 * options in the group are given on the command line, the group is null and none of the `required`
 * constraints are enforced. If any option in the group is given, all `required` options in the
 * group must be given as well.
 */
fun <T : OptionGroup> T.cooccurring(): CoOccurringOptionGroup<T, T?> {
    return CoOccurringOptionGroup(this) { occurred, g, _ ->
        if (occurred == true) g
        else null
    }
}

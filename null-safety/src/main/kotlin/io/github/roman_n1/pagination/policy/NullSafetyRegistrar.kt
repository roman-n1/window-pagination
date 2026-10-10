package io.github.roman_n1.pagination.policy

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

@OptIn(ExperimentalCompilerApi::class)
class NullSafetyRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true
    override val pluginId: String = "window-pagination.null-safety"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messages = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        IrGenerationExtension.registerExtension(NullSafetyExtension(messages))
    }
}

private class NullSafetyExtension(private val messages: MessageCollector) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (file in moduleFragment.files) {
            file.acceptChildrenVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    // Data-class equals and other compiler-generated code are not handwritten APIs.
                    if (element is IrDeclaration && element.origin == IrDeclarationOrigin.FAKE_OVERRIDE) return
                    if (element is IrDeclaration && element.origin.isSynthetic &&
                        element.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR &&
                        element !is IrField
                    ) return
                    if (element.startOffset >= 0) {
                        when (element) {
                            is IrFunction -> checkType(element.returnType, element)
                            is IrValueDeclaration -> checkType(element.type, element)
                            is IrField -> checkType(element.type, element)
                            is IrTypeParameter -> element.superTypes.forEach { checkType(it, element) }
                        }
                    }
                    // Widening a non-null argument to a dependency's nullable input is compiler
                    // adaptation, not a nullable source result. Inspect its children nonetheless.
                    val implicitCast = element is IrTypeOperatorCall && element.operator == IrTypeOperator.IMPLICIT_CAST
                    // A function literal is never null. Its source parameters, return type and
                    // body are checked separately; Compose adds nullable ABI parameters to its
                    // wrapper type after resolution, with no handwritten declaration.
                    if (element is IrExpression && element !is IrFunctionExpression && element.startOffset >= 0 && !implicitCast) {
                        checkType(element.type, element)
                    }
                    element.acceptChildrenVoid(this)
                }

                private fun checkType(type: IrType, element: IrElement) {
                    if (containsNullable(type)) {
                        messages.report(
                            CompilerMessageSeverity.ERROR,
                            "null-safety: nullable or star-projected type of " +
                                (if (element is IrDeclarationWithName) element.name.asString() else "expression") +
                                " (${element.javaClass.simpleName}; $type); use an explicit non-null state",
                            CompilerMessageLocation.create(
                                file.fileEntry.name,
                                file.fileEntry.getLineNumber(element.startOffset) + 1,
                                file.fileEntry.getColumnNumber(element.startOffset) + 1,
                                "",
                            ),
                        )
                    }
                }
            })
        }
    }

    private fun containsNullable(type: IrType): Boolean {
        if (type.isNullable()) return true
        if (type !is IrSimpleType) return false
        return type.arguments.any { argument ->
            when (argument) {
                is IrStarProjection -> true
                is IrTypeProjection -> containsNullable(argument.type)
            }
        }
    }
}

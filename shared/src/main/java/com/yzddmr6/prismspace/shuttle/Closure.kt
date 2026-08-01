package com.yzddmr6.prismspace.shuttle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.shared.BuildConfig
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

private typealias CtxFun<R> = Context.() -> R

internal class Closure(private val functionClass: Class<CtxFun<*>>, private val variables: Array<Any?>): Parcelable {

	fun invoke(context: Context): Any? {
		val constructor = functionClass.declaredConstructors[0].apply { isAccessible = true }
		val args: Array<Any?> = constructor.parameterTypes.map(::getDefaultValue).toTypedArray()
		val variables = variables
		@Suppress("UNCHECKED_CAST") val block = constructor.newInstance(* args) as CtxFun<*>
		block.javaClass.captureFields().forEachIndexed { index, field ->  // Constructor arguments do not matter, as all fields are replaced.
			field.set(block, when (field.type) {
				Context::class.java -> context
				Closure::class.java -> (variables[index] as? Closure)?.invoke(context)
				else -> variables[index] }) }
		return block(context)
	}

	constructor(procedure: CtxFun<*>): this(procedure.javaClass, extractVariablesFromFields(procedure)) {
		val constructors = javaClass.declaredConstructors
		require(constructors.isNotEmpty()) { "The method must have at least one constructor" }
		ClosureCaptureValidator.findViolation(procedure)?.let { violation ->
			val message = "Unsafe Shuttle capture in ${procedure.javaClass.name}: $violation"
			if (BuildConfig.DEBUG) throw IllegalArgumentException(message)
			DiagnosticLog.w(TAG, message)
		}
	}

	private fun getDefaultValue(type: Class<*>)
			= if (type.isPrimitive) ReflectArray.get(ReflectArray.newInstance(type, 1), 0) else null

	override fun toString() = "Closure{${functionClass.name}}"

	override fun describeContents() = 0
	override fun writeToParcel(dest: Parcel, flags: Int) =
			dest.run { writeString(functionClass.name); writeArray(variables) }
	@Suppress("UNCHECKED_CAST") constructor(parcel: Parcel, cl: ClassLoader)
			: this(cl.loadClass(parcel.readString()) as Class<CtxFun<*>>, parcel.readArray(cl)!!)

	companion object CREATOR : Parcelable.ClassLoaderCreator<Closure> {

		override fun createFromParcel(parcel: Parcel, classLoader: ClassLoader) = Closure(parcel, classLoader)
		override fun createFromParcel(parcel: Parcel) = Closure(parcel, Closure::class.java.classLoader!!)
		override fun newArray(size: Int): Array<Closure?> = arrayOfNulls(size)

		// Automatically generated fields for captured variables, by compiler (indeterminate order)
		private fun extractVariablesFromFields(procedure: CtxFun<*>) = procedure.javaClass.captureFields()
			.map { wrapIfNeeded(it.get(procedure)) }.toTypedArray()

		private fun wrapIfNeeded(obj: Any?): Any? = if (obj is Context) null else obj
	}
}

internal data class ClosureCaptureViolation(
	val path: String,
	val declaredType: String,
	val runtimeType: String,
) {
	override fun toString() = "field=$path declared=$declaredType runtime=$runtimeType"
}

/** Pure reflection-based guard for the values Shuttle can reconstruct without changing their type. */
internal object ClosureCaptureValidator {

	fun findViolation(function: Any): ClosureCaptureViolation? = validateFunction(
		function,
		function.javaClass.name,
		Collections.newSetFromMap(IdentityHashMap()),
	)

	private fun validateFunction(function: Any, path: String, visited: MutableSet<Any>): ClosureCaptureViolation? {
		if (!visited.add(function)) return null
		for (field in function.javaClass.captureFields()) {
			val value = if (Context::class.java.isAssignableFrom(field.type)) null else field.get(function)
			validate(value, field.type, "$path.${field.name}", visited)?.let { return it }
		}
		return null
	}

	private fun validate(
		value: Any?,
		declaredType: Class<*>,
		path: String,
		visited: MutableSet<Any>,
	): ClosureCaptureViolation? {
		if (value == null || isSafeScalar(value)) return null

		if (value.javaClass.isArray) {
			if (!visited.add(value)) return null
			val component = declaredType.componentType ?: value.javaClass.componentType
			for (index in 0 until ReflectArray.getLength(value)) {
				validate(ReflectArray.get(value, index), component, "$path[$index]", visited)?.let { return it }
			}
			return null
		}
		if (value is ArrayList<*>) {
			if (!visited.add(value)) return null
			value.forEachIndexed { index, item ->
				validate(item, item?.javaClass ?: Any::class.java, "$path[$index]", visited)?.let { return it }
			}
			return null
		}
		if (value is Bundle) {
			if (!visited.add(value)) return null
			value.keySet().forEach { key ->
				val item = value.get(key)
				validate(item, item?.javaClass ?: Any::class.java, "$path[$key]", visited)?.let { return it }
			}
			return null
		}
		if (value is Intent) {
			if (!visited.add(value)) return null
			val extras = value.extras ?: return null
			return validate(extras, Bundle::class.java, "$path.extras", visited)
		}
		if (value is kotlin.Function<*>) return validateFunction(value, path, visited)

		val runtimeType = value.javaClass
		if (Parcelable::class.java.isAssignableFrom(runtimeType) &&
			runtimeType == declaredType &&
			Modifier.isFinal(runtimeType.modifiers) &&
			runtimeType.name.startsWith("android.")) return null

		return ClosureCaptureViolation(path, declaredType.name, runtimeType.name)
	}

	private fun isSafeScalar(value: Any): Boolean = when (value) {
		is Boolean, is Byte, is Char, is Short, is Int, is Long, is Float, is Double,
		is String, is CharSequence, is UserHandle, is ComponentName, is Uri -> true
		else -> false
	}
}

private fun Class<*>.captureFields(): List<Field> = declaredFields.filter { field ->
	if (Modifier.isStatic(field.modifiers)) false else {
		field.isAccessible = true
		true
	}
}

private const val TAG = "Prism.Closure"

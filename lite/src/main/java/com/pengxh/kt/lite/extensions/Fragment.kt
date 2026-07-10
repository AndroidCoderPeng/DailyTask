package com.pengxh.kt.lite.extensions

import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.viewbinding.ViewBinding
import java.lang.reflect.InvocationTargetException
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

inline fun <reified VB : ViewBinding> Fragment.bindView() = FragmentBindingDelegate(VB::class.java)

class FragmentBindingDelegate<VB : ViewBinding>(private val clazz: Class<VB>) :
    ReadOnlyProperty<Fragment, VB> {

    private var binding: VB? = null
    private var observerAdded = false
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            binding = null
            observerAdded = false
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Fragment, property: KProperty<*>): VB {
        if (binding == null) {
            try {
                val method = clazz.getMethod("inflate", LayoutInflater::class.java)
                binding = method.invoke(null, thisRef.layoutInflater) as VB
            } catch (e: NoSuchMethodException) {
                throw IllegalStateException("Failed to find inflate method for ${clazz.name}", e)
            } catch (e: IllegalAccessException) {
                throw IllegalStateException("Failed to access inflate method for ${clazz.name}", e)
            } catch (e: InvocationTargetException) {
                throw IllegalStateException("Failed to invoke inflate method for ${clazz.name}", e)
            } catch (e: IllegalStateException) {
                throw IllegalStateException(
                    "The property of ${property.name} has been destroyed.", e
                )
            }
        }

        val currentState = thisRef.viewLifecycleOwner.lifecycle.currentState
        if (currentState.isAtLeast(Lifecycle.State.INITIALIZED) && !observerAdded) {
            thisRef.viewLifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            observerAdded = true
        }

        return binding ?: throw IllegalStateException("Binding is not initialized")
    }
}
package dev.sort.doris.model

import com.intellij.database.Dbms
import com.intellij.database.model.ModelTextStorage
import com.intellij.database.model.NameValueGetter
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.meta.BasicMetaModel
import com.intellij.database.model.meta.BasicMetaObject
import com.intellij.database.model.meta.BasicMetaProperty
import java.lang.reflect.Constructor
import java.util.function.BiConsumer

/**
 * Reflective bridge over the ONE binary break between platform generations 261 and 262 that this
 * plugin's bytecode would otherwise hard-reference (COMPAT-262.md items 2 and 3):
 *
 *  - 261: `BasicMetaModel(Dbms, BasicMetaObject, Class, com.intellij.util.Function)`
 *  - 262: `BasicMetaModel(Dbms, BasicMetaObject, Class, java.util.function.Function)`
 *
 * and identically for the 7-arg `BasicMetaObject` constructor (its factory param changed from
 * `com.intellij.util.Function` to `java.util.function.Function`; 262 also *adds* an 8-arg overload
 * with a trailing `Level`, which we deliberately ignore). Both CLASSES exist unchanged in both
 * generations — only the constructor descriptors differ — so the classes stay statically referenced
 * and only the constructor invocations go through reflection. That removes the invokespecial
 * descriptors that made the plugin verifier flag the artifact as 262-incompatible, letting one
 * artifact declare `untilBuild = 262.*`.
 *
 * The factory arguments are typed `com.intellij.util.Function`, which `extends
 * java.util.function.Function` (verified in util-8.jar): a single lambda object therefore satisfies
 * BOTH generations' parameter types at invoke time.
 *
 * Constructor lookup is by arity + leading parameter types (never by the changed `Function` slot)
 * and the resolved [Constructor] is cached per class (lazy, thread-safe).
 */
object DorisMetaCompat {

    /** `BasicMetaModel`: the single 4-arg ctor, identified by its stable leading params. */
    private val metaModelCtor: Constructor<*> by lazy {
        BasicMetaModel::class.java.declaredConstructors
            .filter { it.parameterCount == 4 }
            .filter {
                it.parameterTypes[0] == Dbms::class.java &&
                    it.parameterTypes[1] == BasicMetaObject::class.java &&
                    it.parameterTypes[2] == Class::class.java
            }
            .also { require(it.size == 1) { "Expected exactly one 4-arg BasicMetaModel(Dbms, BasicMetaObject, Class, *) constructor, found ${it.size}: $it (platform meta-model API changed again? see COMPAT-262.md)" } }
            .single()
    }

    /** `BasicMetaObject`: the 7-arg ctor (262 adds an 8-arg overload we skip by arity). */
    private val metaObjectCtor: Constructor<*> by lazy {
        BasicMetaObject::class.java.declaredConstructors
            .filter { it.parameterCount == 7 }
            .filter {
                it.parameterTypes[0] == ObjectKind::class.java &&
                    it.parameterTypes[1] == Class::class.java
            }
            .also { require(it.size == 1) { "Expected exactly one 7-arg BasicMetaObject(ObjectKind, Class, ...) constructor, found ${it.size}: $it (platform meta-model API changed again? see COMPAT-262.md)" } }
            .single()
    }

    /**
     * `new BasicMetaModel(dbms, root, modelClass, modelFactory)` for both generations. The
     * [modelFactory] must be a `com.intellij.util.Function` instance so it is assignable to the
     * 261 (`com.intellij.util.Function`) and 262 (`java.util.function.Function`) parameter alike.
     */
    fun <M : BasicModModel> newMetaModel(
        dbms: Dbms,
        root: BasicMetaObject<*>,
        modelClass: Class<M>,
        modelFactory: com.intellij.util.Function<ModelTextStorage, out M>,
    ): BasicMetaModel<M> {
        // Unchecked: reflection erases the ctor's generics; the runtime object is exactly the
        // BasicMetaModel<M> the direct 261 call produced before (COMPAT-262.md).
        @Suppress("UNCHECKED_CAST")
        return metaModelCtor.newInstance(dbms, root, modelClass, modelFactory) as BasicMetaModel<M>
    }

    /** `new BasicMetaObject(kind, api, dataFactory, deserializer, props, refs, children)` for both generations. */
    fun newMetaObject(
        kind: ObjectKind,
        api: Class<out BasicElement>,
        dataFactory: com.intellij.util.Function<BasicMetaObject<BasicElement>, out BasicElement>,
        deserializer: BiConsumer<BasicElement, NameValueGetter<String>>,
        properties: Array<BasicMetaProperty<BasicElement, *>>,
        references: Array<BasicMetaProperty<BasicElement, *>>,
        children: Array<BasicMetaObject<*>>,
    ): BasicMetaObject<BasicElement> {
        // Unchecked: same erasure story as newMetaModel — see COMPAT-262.md.
        @Suppress("UNCHECKED_CAST")
        return metaObjectCtor.newInstance(kind, api, dataFactory, deserializer, properties, references, children) as BasicMetaObject<BasicElement>
    }
}

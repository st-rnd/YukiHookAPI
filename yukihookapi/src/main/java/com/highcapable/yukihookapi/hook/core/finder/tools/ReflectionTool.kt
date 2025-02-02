/*
 * YukiHookAPI - An efficient Hook API and Xposed Module solution built in Kotlin.
 * Copyright (C) 2019-2023 HighCapable
 * https://github.com/fankes/YukiHookAPI
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * This file is Created by fankes on 2022/3/27.
 */
@file:Suppress("KotlinConstantConditions")

package com.highcapable.yukihookapi.hook.core.finder.tools

import com.highcapable.yukihookapi.hook.core.finder.base.data.BaseRulesData
import com.highcapable.yukihookapi.hook.core.finder.classes.data.ClassRulesData
import com.highcapable.yukihookapi.hook.core.finder.members.data.ConstructorRulesData
import com.highcapable.yukihookapi.hook.core.finder.members.data.FieldRulesData
import com.highcapable.yukihookapi.hook.core.finder.members.data.MemberRulesData
import com.highcapable.yukihookapi.hook.core.finder.members.data.MethodRulesData
import com.highcapable.yukihookapi.hook.core.finder.store.ReflectsCacheStore
import com.highcapable.yukihookapi.hook.factory.*
import com.highcapable.yukihookapi.hook.log.yLoggerW
import com.highcapable.yukihookapi.hook.type.defined.UndefinedType
import com.highcapable.yukihookapi.hook.type.defined.VagueType
import com.highcapable.yukihookapi.hook.type.java.DalvikBaseDexClassLoader
import com.highcapable.yukihookapi.hook.type.java.NoClassDefFoundErrorClass
import com.highcapable.yukihookapi.hook.type.java.NoSuchFieldErrorClass
import com.highcapable.yukihookapi.hook.type.java.NoSuchMethodErrorClass
import com.highcapable.yukihookapi.hook.utils.*
import com.highcapable.yukihookapi.hook.utils.value
import com.highcapable.yukihookapi.hook.xposed.parasitic.AppParasitics
import dalvik.system.BaseDexClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.*
import kotlin.math.abs

/**
 * 这是一个对 [Class]、[Member] 查找的工具实现类
 */
@PublishedApi
internal object ReflectionTool {

    /** 当前工具类的标签 */
    private const val TAG = "YukiHookAPI#ReflectionTool"

    /**
     * 写出当前 [ClassLoader] 下所有 [Class] 名称数组
     * @param loader 当前使用的 [ClassLoader]
     * @return [List]<[String]>
     * @throws IllegalStateException 如果 [loader] 不是 [BaseDexClassLoader]
     */
    internal fun findDexClassList(loader: ClassLoader?) = ReflectsCacheStore.findDexClassList(loader.hashCode())
        ?: DalvikBaseDexClassLoader.field { name = "pathList" }.ignored().get(loader.value().let {
            while (it.value !is BaseDexClassLoader) {
                if (it.value?.parent != null) it.value = it.value?.parent
                else error("ClassLoader [$loader] is not a DexClassLoader")
            }; it.value ?: error("ClassLoader [$loader] load failed")
        }).current(ignored = true)?.field { name = "dexElements" }?.array<Any>()?.flatMap { element ->
            element.current(ignored = true).field { name = "dexFile" }.current(ignored = true)
                ?.method { name = "entries" }?.invoke<Enumeration<String>>()?.toList().orEmpty()
        }.orEmpty().also { if (it.isNotEmpty()) ReflectsCacheStore.putDexClassList(loader.hashCode(), it) }

    /**
     * 使用字符串类名查找 [Class] 是否存在
     * @param name [Class] 完整名称
     * @param loader [Class] 所在的 [ClassLoader]
     * @return [Boolean]
     */
    internal fun hasClassByName(name: String, loader: ClassLoader?) = runCatching { findClassByName(name, loader); true }.getOrNull() ?: false

    /**
     * 使用字符串类名获取 [Class]
     * @param name [Class] 完整名称
     * @param loader [Class] 所在的 [ClassLoader]
     * @param initialize 是否初始化 [Class] 的静态方法块 - 默认否
     * @return [Class]
     * @throws NoClassDefFoundError 如果找不到 [Class] 或设置了错误的 [ClassLoader]
     */
    @PublishedApi
    internal fun findClassByName(name: String, loader: ClassLoader?, initialize: Boolean = false): Class<*> {
        val hashCode = ("[$name][$loader]").hashCode()

        /**
         * 获取 [Class.forName] 的 [Class] 对象
         * @param name [Class] 完整名称
         * @param initialize 是否初始化 [Class] 的静态方法块
         * @param loader [Class] 所在的 [ClassLoader] - 默认为 [AppParasitics.baseClassLoader]
         * @return [Class]
         */
        fun classForName(name: String, initialize: Boolean, loader: ClassLoader? = AppParasitics.baseClassLoader) =
            Class.forName(name, initialize, loader)

        /**
         * 使用默认方式和 [ClassLoader] 装载 [Class]
         * @return [Class] or null
         */
        fun loadWithDefaultClassLoader() = if (initialize.not()) loader?.loadClass(name) else classForName(name, initialize, loader)
        return ReflectsCacheStore.findClass(hashCode) ?: runCatching {
            (loadWithDefaultClassLoader() ?: classForName(name, initialize)).also { ReflectsCacheStore.putClass(hashCode, it) }
        }.getOrNull() ?: throw createException(loader ?: AppParasitics.baseClassLoader, name = "Class", "name:[$name]")
    }

    /**
     * 查找任意 [Class] 或一组 [Class]
     * @param loaderSet 类所在 [ClassLoader]
     * @param rulesData 规则查找数据
     * @return [HashSet]<[Class]>
     * @throws IllegalStateException 如果 [loaderSet] 为 null 或未设置任何条件
     * @throws NoClassDefFoundError 如果找不到 [Class]
     */
    internal fun findClasses(loaderSet: ClassLoader?, rulesData: ClassRulesData) = rulesData.createResult {
        ReflectsCacheStore.findClasses(hashCode(loaderSet)) ?: hashSetOf<Class<*>>().also { classes ->
            /**
             * 开始查找作业
             * @param instance 当前 [Class] 实例
             */
            fun startProcess(instance: Class<*>) {
                conditions {
                    fromPackages.takeIf { it.isNotEmpty() }?.also { and(true) }
                    fullName?.also { it.equals(instance, it.TYPE_NAME).also { e -> if (it.isOptional) opt(e) else and(e) } }
                    simpleName?.also { it.equals(instance, it.TYPE_SIMPLE_NAME).also { e -> if (it.isOptional) opt(e) else and(e) } }
                    singleName?.also { it.equals(instance, it.TYPE_SINGLE_NAME).also { e -> if (it.isOptional) opt(e) else and(e) } }
                    fullNameConditions?.also { instance.name.also { n -> runCatching { and(it(n.cast(), n)) } } }
                    simpleNameConditions?.also { instance.simpleName.also { n -> runCatching { and(it(n.cast(), n)) } } }
                    singleNameConditions?.also { classSingleName(instance).also { n -> runCatching { and(it(n.cast(), n)) } } }
                    modifiers?.also { runCatching { and(it(instance.cast())) } }
                    extendsClass.takeIf { it.isNotEmpty() }?.also { and(instance.hasExtends && it.contains(instance.superclass.name)) }
                    implementsClass.takeIf { it.isNotEmpty() }
                        ?.also { and(instance.interfaces.isNotEmpty() && instance.interfaces.any { e -> it.contains(e.name) }) }
                    enclosingClass.takeIf { it.isNotEmpty() }
                        ?.also { and(instance.enclosingClass != null && it.contains(instance.enclosingClass.name)) }
                    isAnonymousClass?.also { and(instance.isAnonymousClass && it) }
                    isNoExtendsClass?.also { and(instance.hasExtends.not() && it) }
                    isNoImplementsClass?.also { and(instance.interfaces.isEmpty() && it) }
                    /**
                     * 匹配 [MemberRulesData]
                     * @param size [Member] 个数
                     * @param result 回调是否匹配
                     */
                    fun MemberRulesData.matchCount(size: Int, result: (Boolean) -> Unit) {
                        takeIf { it.isInitializeOfMatch }?.also { rule ->
                            rule.conditions {
                                value.matchCount.takeIf { it >= 0 }?.also { and(it == size) }
                                value.matchCountRange.takeIf { it.isEmpty().not() }?.also { and(size in it) }
                                value.matchCountConditions?.also { runCatching { and(it(size.cast(), size)) } }
                            }.finally { result(true) }.without { result(false) }
                        } ?: result(true)
                    }

                    /**
                     * 检查类型中的 [Class] 是否存在 - 即不存在 [UndefinedType]
                     * @param type 类型
                     * @return [Boolean]
                     */
                    fun MemberRulesData.exists(vararg type: Any?): Boolean {
                        if (type.isEmpty()) return true
                        for (i in type.indices) if (type[i] == UndefinedType) {
                            yLoggerW(msg = "$objectName type[$i] mistake, it will be ignored in current conditions")
                            return false
                        }
                        return true
                    }
                    memberRules.takeIf { it.isNotEmpty() }?.forEach { rule ->
                        instance.existMembers?.apply {
                            var numberOfFound = 0
                            if (rule.isInitializeOfSuper) forEach { member ->
                                rule.conditions {
                                    value.modifiers?.also { runCatching { and(it(member.cast())) } }
                                }.finally { numberOfFound++ }
                            }.run { rule.matchCount(numberOfFound) { and(it && numberOfFound > 0) } }
                            else rule.matchCount(size) { and(it) }
                        }
                    }
                    fieldRules.takeIf { it.isNotEmpty() }?.forEach { rule ->
                        instance.existFields?.apply {
                            var numberOfFound = 0
                            if (rule.isInitialize) forEach { field ->
                                rule.conditions {
                                    value.type?.takeIf { value.exists(it) }?.also { and(it == field.type) }
                                    value.name.takeIf { it.isNotBlank() }?.also { and(it == field.name) }
                                    value.modifiers?.also { runCatching { and(it(field.cast())) } }
                                    value.nameConditions?.also { field.name.also { n -> runCatching { and(it(n.cast(), n)) } } }
                                    value.typeConditions?.also { field.also { t -> runCatching { and(it(t.type(), t.type)) } } }
                                }.finally { numberOfFound++ }
                            }.run { rule.matchCount(numberOfFound) { and(it && numberOfFound > 0) } }
                            else rule.matchCount(size) { and(it) }
                        }
                    }
                    methodRules.takeIf { it.isNotEmpty() }?.forEach { rule ->
                        instance.existMethods?.apply {
                            var numberOfFound = 0
                            if (rule.isInitialize) forEach { method ->
                                rule.conditions {
                                    value.name.takeIf { it.isNotBlank() }?.also { and(it == method.name) }
                                    value.returnType?.takeIf { value.exists(it) }?.also { and(it == method.returnType) }
                                    value.returnTypeConditions
                                        ?.also { method.also { r -> runCatching { and(it(r.returnType(), r.returnType)) } } }
                                    value.paramCount.takeIf { it >= 0 }?.also { and(method.parameterTypes.size == it) }
                                    value.paramCountRange.takeIf { it.isEmpty().not() }?.also { and(method.parameterTypes.size in it) }
                                    value.paramCountConditions
                                        ?.also { method.parameterTypes.size.also { s -> runCatching { and(it(s.cast(), s)) } } }
                                    value.paramTypes?.takeIf { value.exists(*it) }?.also { and(paramTypesEq(it, method.parameterTypes)) }
                                    value.paramTypesConditions
                                        ?.also { method.also { t -> runCatching { and(it(t.paramTypes(), t.parameterTypes)) } } }
                                    value.modifiers?.also { runCatching { and(it(method.cast())) } }
                                    value.nameConditions?.also { method.name.also { n -> runCatching { and(it(n.cast(), n)) } } }
                                }.finally { numberOfFound++ }
                            }.run { rule.matchCount(numberOfFound) { and(it && numberOfFound > 0) } }
                            else rule.matchCount(size) { and(it) }
                        }
                    }
                    constroctorRules.takeIf { it.isNotEmpty() }?.forEach { rule ->
                        instance.existConstructors?.apply {
                            var numberOfFound = 0
                            if (rule.isInitialize) forEach { constructor ->
                                rule.conditions {
                                    value.paramCount.takeIf { it >= 0 }?.also { and(constructor.parameterTypes.size == it) }
                                    value.paramCountRange.takeIf { it.isEmpty().not() }?.also { and(constructor.parameterTypes.size in it) }
                                    value.paramCountConditions
                                        ?.also { constructor.parameterTypes.size.also { s -> runCatching { and(it(s.cast(), s)) } } }
                                    value.paramTypes?.takeIf { value.exists(*it) }?.also { and(paramTypesEq(it, constructor.parameterTypes)) }
                                    value.paramTypesConditions
                                        ?.also { constructor.also { t -> runCatching { and(it(t.paramTypes(), t.parameterTypes)) } } }
                                    value.modifiers?.also { runCatching { and(it(constructor.cast())) } }
                                }.finally { numberOfFound++ }
                            }.run { rule.matchCount(numberOfFound) { and(it && numberOfFound > 0) } }
                            else rule.matchCount(size) { and(it) }
                        }
                    }
                }.finally { classes.add(instance) }
            }
            findDexClassList(loaderSet).takeIf { it.isNotEmpty() }?.forEach { className ->
                /** 分离包名 → com.demo.Test → com.demo (获取最后一个 "." + 简单类名的长度) → 由于末位存在 "." 最后要去掉 1 个长度 */
                (if (className.contains("."))
                    className.substring(0, className.length - className.split(".").let { it[it.lastIndex] }.length - 1)
                else className).also { packageName ->
                    if ((fromPackages.isEmpty() || fromPackages.any {
                            if (it.isAbsolute) packageName == it.name else packageName.startsWith(it.name)
                        }) && className.hasClass(loaderSet)
                    ) startProcess(className.toClass(loaderSet))
                }
            }
        }.takeIf { it.isNotEmpty() }?.also { ReflectsCacheStore.putClasses(hashCode(loaderSet), it) } ?: throwNotFoundError(loaderSet)
    }

    /**
     * 查找任意 [Field] 或一组 [Field]
     * @param classSet [Field] 所在类
     * @param rulesData 规则查找数据
     * @return [HashSet]<[Field]>
     * @throws IllegalStateException 如果未设置任何条件或 [FieldRulesData.type] 目标类不存在
     * @throws NoSuchFieldError 如果找不到 [Field]
     */
    internal fun findFields(classSet: Class<*>?, rulesData: FieldRulesData) = rulesData.createResult {
        if (type == UndefinedType) error("Field match type class is not found")
        if (classSet == null) return@createResult hashSetOf()
        ReflectsCacheStore.findFields(hashCode(classSet)) ?: hashSetOf<Field>().also { fields ->
            classSet.existFields?.also { declares ->
                var iType = -1
                var iName = -1
                var iModify = -1
                var iNameCds = -1
                var iTypeCds = -1
                val iLType = type?.let(matchIndex) { e -> declares.filter { e == it.type }.lastIndex } ?: -1
                val iLName = name.takeIf(matchIndex) { it.isNotBlank() }?.let { e -> declares.filter { e == it.name }.lastIndex } ?: -1
                val iLModify = modifiers?.let(matchIndex) { e -> declares.filter { runOrFalse { e(it.cast()) } }.lastIndex } ?: -1
                val iLNameCds = nameConditions
                    ?.let(matchIndex) { e -> declares.filter { it.name.let { n -> runOrFalse { e(n.cast(), n) } } }.lastIndex } ?: -1
                val iLTypeCds = typeConditions?.let(matchIndex) { e -> declares.filter { runOrFalse { e(it.type(), it.type) } }.lastIndex } ?: -1
                declares.forEachIndexed { index, instance ->
                    conditions {
                        type?.also {
                            and((it == instance.type).let { hold ->
                                if (hold) iType++
                                hold && matchIndex.compare(iType, iLType)
                            })
                        }
                        name.takeIf { it.isNotBlank() }?.also {
                            and((it == instance.name).let { hold ->
                                if (hold) iName++
                                hold && matchIndex.compare(iName, iLName)
                            })
                        }
                        modifiers?.also {
                            and(runOrFalse { it(instance.cast()) }.let { hold ->
                                if (hold) iModify++
                                hold && matchIndex.compare(iModify, iLModify)
                            })
                        }
                        nameConditions?.also {
                            and(instance.name.let { n -> runOrFalse { it(n.cast(), n) } }.let { hold ->
                                if (hold) iNameCds++
                                hold && matchIndex.compare(iNameCds, iLNameCds)
                            })
                        }
                        typeConditions?.also {
                            and(instance.let { t -> runOrFalse { it(t.type(), t.type) } }.let { hold ->
                                if (hold) iTypeCds++
                                hold && matchIndex.compare(iTypeCds, iLTypeCds)
                            })
                        }
                        orderIndex.compare(index, declares.lastIndex) { and(it) }
                    }.finally { fields.add(instance.apply { isAccessible = true }) }
                }
            }
        }.takeIf { it.isNotEmpty() }?.also { ReflectsCacheStore.putFields(hashCode(classSet), it) } ?: findSuperOrThrow(classSet)
    }

    /**
     * 查找任意 [Method] 或一组 [Method]
     * @param classSet [Method] 所在类
     * @param rulesData 规则查找数据
     * @return [HashSet]<[Method]>
     * @throws IllegalStateException 如果未设置任何条件或 [MethodRulesData.paramTypes] 以及 [MethodRulesData.returnType] 目标类不存在
     * @throws NoSuchMethodError 如果找不到 [Method]
     */
    internal fun findMethods(classSet: Class<*>?, rulesData: MethodRulesData) = rulesData.createResult {
        if (returnType == UndefinedType) error("Method match returnType class is not found")
        if (classSet == null) return@createResult hashSetOf()
        paramTypes?.takeIf { it.isNotEmpty() }
            ?.forEachIndexed { p, it -> if (it == UndefinedType) error("Method match paramType[$p] class is not found") }
        ReflectsCacheStore.findMethods(hashCode(classSet)) ?: hashSetOf<Method>().also { methods ->
            classSet.existMethods?.also { declares ->
                var iReturnType = -1
                var iReturnTypeCds = -1
                var iParamTypes = -1
                var iParamTypesCds = -1
                var iParamCount = -1
                var iParamCountRange = -1
                var iParamCountCds = -1
                var iName = -1
                var iModify = -1
                var iNameCds = -1
                val iLReturnType = returnType?.let(matchIndex) { e -> declares.filter { e == it.returnType }.lastIndex } ?: -1
                val iLReturnTypeCds = returnTypeConditions
                    ?.let(matchIndex) { e -> declares.filter { runOrFalse { e(it.returnType(), it.returnType) } }.lastIndex } ?: -1
                val iLParamCount = paramCount.takeIf(matchIndex) { it >= 0 }
                    ?.let { e -> declares.filter { e == it.parameterTypes.size }.lastIndex } ?: -1
                val iLParamCountRange = paramCountRange.takeIf(matchIndex) { it.isEmpty().not() }
                    ?.let { e -> declares.filter { it.parameterTypes.size in e }.lastIndex } ?: -1
                val iLParamCountCds = paramCountConditions?.let(matchIndex) { e ->
                    declares.filter { it.parameterTypes.size.let { s -> runOrFalse { e(s.cast(), s) } } }.lastIndex
                } ?: -1
                val iLParamTypes = paramTypes?.let(matchIndex) { e -> declares.filter { paramTypesEq(e, it.parameterTypes) }.lastIndex } ?: -1
                val iLParamTypesCds = paramTypesConditions
                    ?.let(matchIndex) { e -> declares.filter { runOrFalse { e(it.paramTypes(), it.parameterTypes) } }.lastIndex } ?: -1
                val iLName = name.takeIf(matchIndex) { it.isNotBlank() }?.let { e -> declares.filter { e == it.name }.lastIndex } ?: -1
                val iLModify = modifiers?.let(matchIndex) { e -> declares.filter { runOrFalse { e(it.cast()) } }.lastIndex } ?: -1
                val iLNameCds = nameConditions
                    ?.let(matchIndex) { e -> declares.filter { it.name.let { n -> runOrFalse { e(n.cast(), n) } } }.lastIndex } ?: -1
                declares.forEachIndexed { index, instance ->
                    conditions {
                        name.takeIf { it.isNotBlank() }?.also {
                            and((it == instance.name).let { hold ->
                                if (hold) iName++
                                hold && matchIndex.compare(iName, iLName)
                            })
                        }
                        returnType?.also {
                            and((it == instance.returnType).let { hold ->
                                if (hold) iReturnType++
                                hold && matchIndex.compare(iReturnType, iLReturnType)
                            })
                        }
                        returnTypeConditions?.also {
                            and(instance.let { r -> runOrFalse { it(r.returnType(), r.returnType) } }.let { hold ->
                                if (hold) iReturnTypeCds++
                                hold && matchIndex.compare(iReturnTypeCds, iLReturnTypeCds)
                            })
                        }
                        paramCount.takeIf { it >= 0 }?.also {
                            and((instance.parameterTypes.size == it).let { hold ->
                                if (hold) iParamCount++
                                hold && matchIndex.compare(iParamCount, iLParamCount)
                            })
                        }
                        paramCountRange.takeIf { it.isEmpty().not() }?.also {
                            and((instance.parameterTypes.size in it).let { hold ->
                                if (hold) iParamCountRange++
                                hold && matchIndex.compare(iParamCountRange, iLParamCountRange)
                            })
                        }
                        paramCountConditions?.also {
                            and(instance.parameterTypes.size.let { s -> runOrFalse { it(s.cast(), s) } }.let { hold ->
                                if (hold) iParamCountCds++
                                hold && matchIndex.compare(iParamCountCds, iLParamCountCds)
                            })
                        }
                        paramTypes?.also {
                            and(paramTypesEq(it, instance.parameterTypes).let { hold ->
                                if (hold) iParamTypes++
                                hold && matchIndex.compare(iParamTypes, iLParamTypes)
                            })
                        }
                        paramTypesConditions?.also {
                            and(instance.let { t -> runOrFalse { it(t.paramTypes(), t.parameterTypes) } }.let { hold ->
                                if (hold) iParamTypesCds++
                                hold && matchIndex.compare(iParamTypesCds, iLParamTypesCds)
                            })
                        }
                        modifiers?.also {
                            and(runOrFalse { it(instance.cast()) }.let { hold ->
                                if (hold) iModify++
                                hold && matchIndex.compare(iModify, iLModify)
                            })
                        }
                        nameConditions?.also {
                            and(instance.name.let { n -> runOrFalse { it(n.cast(), n) } }.let { hold ->
                                if (hold) iNameCds++
                                hold && matchIndex.compare(iNameCds, iLNameCds)
                            })
                        }
                        orderIndex.compare(index, declares.lastIndex) { and(it) }
                    }.finally { methods.add(instance.apply { isAccessible = true }) }
                }
            }
        }.takeIf { it.isNotEmpty() }?.also { ReflectsCacheStore.putMethods(hashCode(classSet), it) } ?: findSuperOrThrow(classSet)
    }

    /**
     * 查找任意 [Constructor] 或一组 [Constructor]
     * @param classSet [Constructor] 所在类
     * @param rulesData 规则查找数据
     * @return [HashSet]<[Constructor]>
     * @throws IllegalStateException 如果未设置任何条件或 [ConstructorRulesData.paramTypes] 目标类不存在
     * @throws NoSuchMethodError 如果找不到 [Constructor]
     */
    internal fun findConstructors(classSet: Class<*>?, rulesData: ConstructorRulesData) = rulesData.createResult {
        if (classSet == null) return@createResult hashSetOf()
        paramTypes?.takeIf { it.isNotEmpty() }
            ?.forEachIndexed { p, it -> if (it == UndefinedType) error("Constructor match paramType[$p] class is not found") }
        ReflectsCacheStore.findConstructors(hashCode(classSet)) ?: hashSetOf<Constructor<*>>().also { constructors ->
            classSet.existConstructors?.also { declares ->
                var iParamTypes = -1
                var iParamTypesCds = -1
                var iParamCount = -1
                var iParamCountRange = -1
                var iParamCountCds = -1
                var iModify = -1
                val iLParamCount = paramCount.takeIf(matchIndex) { it >= 0 }
                    ?.let { e -> declares.filter { e == it.parameterTypes.size }.lastIndex } ?: -1
                val iLParamCountRange = paramCountRange.takeIf(matchIndex) { it.isEmpty().not() }
                    ?.let { e -> declares.filter { it.parameterTypes.size in e }.lastIndex } ?: -1
                val iLParamCountCds = paramCountConditions?.let(matchIndex) { e ->
                    declares.filter { it.parameterTypes.size.let { s -> runOrFalse { e(s.cast(), s) } } }.lastIndex
                } ?: -1
                val iLParamTypes = paramTypes?.let(matchIndex) { e -> declares.filter { paramTypesEq(e, it.parameterTypes) }.lastIndex } ?: -1
                val iLParamTypesCds = paramTypesConditions
                    ?.let(matchIndex) { e -> declares.filter { runOrFalse { e(it.paramTypes(), it.parameterTypes) } }.lastIndex } ?: -1
                val iLModify = modifiers?.let(matchIndex) { e -> declares.filter { runOrFalse { e(it.cast()) } }.lastIndex } ?: -1
                declares.forEachIndexed { index, instance ->
                    conditions {
                        paramCount.takeIf { it >= 0 }?.also {
                            and((instance.parameterTypes.size == it).let { hold ->
                                if (hold) iParamCount++
                                hold && matchIndex.compare(iParamCount, iLParamCount)
                            })
                        }
                        paramCountRange.takeIf { it.isEmpty().not() }?.also {
                            and((instance.parameterTypes.size in it).let { hold ->
                                if (hold) iParamCountRange++
                                hold && matchIndex.compare(iParamCountRange, iLParamCountRange)
                            })
                        }
                        paramCountConditions?.also {
                            and(instance.parameterTypes.size.let { s -> runOrFalse { it(s.cast(), s) } }.let { hold ->
                                if (hold) iParamCountCds++
                                hold && matchIndex.compare(iParamCountCds, iLParamCountCds)
                            })
                        }
                        paramTypes?.also {
                            and(paramTypesEq(it, instance.parameterTypes).let { hold ->
                                if (hold) iParamTypes++
                                hold && matchIndex.compare(iParamTypes, iLParamTypes)
                            })
                        }
                        paramTypesConditions?.also {
                            and(instance.let { t -> runOrFalse { it(t.paramTypes(), t.parameterTypes) } }.let { hold ->
                                if (hold) iParamTypesCds++
                                hold && matchIndex.compare(iParamTypesCds, iLParamTypesCds)
                            })
                        }
                        modifiers?.also {
                            and(runOrFalse { it(instance.cast()) }.let { hold ->
                                if (hold) iModify++
                                hold && matchIndex.compare(iModify, iLModify)
                            })
                        }
                        orderIndex.compare(index, declares.lastIndex) { and(it) }
                    }.finally { constructors.add(instance.apply { isAccessible = true }) }
                }
            }
        }.takeIf { it.isNotEmpty() }?.also { ReflectsCacheStore.putConstructors(hashCode(classSet), it) } ?: findSuperOrThrow(classSet)
    }

    /**
     * 比较位置下标的前后顺序
     * @param need 当前位置
     * @param last 最后位置
     * @return [Boolean] 返回是否成立
     */
    private fun Pair<Int, Boolean>?.compare(need: Int, last: Int) = this == null || ((first >= 0 && first == need && second) ||
            (first < 0 && abs(first) == (last - need) && second) || (last == need && second.not()))

    /**
     * 比较位置下标的前后顺序
     * @param need 当前位置
     * @param last 最后位置
     * @param result 回调是否成立
     */
    private fun Pair<Int, Boolean>?.compare(need: Int, last: Int, result: (Boolean) -> Unit) {
        if (this == null) return
        ((first >= 0 && first == need && second) ||
                (first < 0 && abs(first) == (last - need) && second) ||
                (last == need && second.not())).also(result)
    }

    /**
     * 创建查找结果方法体
     * @param result 回调方法体
     * @return [T]
     * @throws IllegalStateException 如果没有 [BaseRulesData.isInitialize]
     */
    private inline fun <reified T, R : BaseRulesData> R.createResult(result: R.() -> T): T {
        when (this) {
            is FieldRulesData -> isInitialize.not()
            is MethodRulesData -> isInitialize.not()
            is ConstructorRulesData -> isInitialize.not()
            is ClassRulesData -> isInitialize.not()
            else -> true
        }.takeIf { it }?.also { error("You must set a condition when finding a $objectName") }
        return result(this)
    }

    /**
     * 在 [Class.getSuperclass] 中查找或抛出异常
     * @param classSet 所在类
     * @return [T]
     * @throws NoSuchFieldError 继承于方法 [throwNotFoundError] 的异常
     * @throws NoSuchMethodError 继承于方法 [throwNotFoundError] 的异常
     * @throws IllegalStateException 如果 [R] 的类型错误
     */
    private inline fun <reified T, R : MemberRulesData> R.findSuperOrThrow(classSet: Class<*>): T = when (this) {
        is FieldRulesData ->
            if (isFindInSuper && classSet.hasExtends)
                findFields(classSet.superclass, rulesData = this) as T
            else throwNotFoundError(classSet)
        is MethodRulesData ->
            if (isFindInSuper && classSet.hasExtends)
                findMethods(classSet.superclass, rulesData = this) as T
            else throwNotFoundError(classSet)
        is ConstructorRulesData ->
            if (isFindInSuper && classSet.hasExtends)
                findConstructors(classSet.superclass, rulesData = this) as T
            else throwNotFoundError(classSet)
        else -> error("Type [$this] not allowed")
    }

    /**
     * 抛出找不到 [Class]、[Member] 的异常
     * @param instanceSet 所在 [ClassLoader] or [Class]
     * @throws NoClassDefFoundError 如果找不到 [Class]
     * @throws NoSuchFieldError 如果找不到 [Field]
     * @throws NoSuchMethodError 如果找不到 [Method] or [Constructor]
     * @throws IllegalStateException 如果 [BaseRulesData] 的类型错误
     */
    private fun BaseRulesData.throwNotFoundError(instanceSet: Any?): Nothing = when (this) {
        is FieldRulesData -> throw createException(instanceSet, objectName, *templates)
        is MethodRulesData -> throw createException(instanceSet, objectName, *templates)
        is ConstructorRulesData -> throw createException(instanceSet, objectName, *templates)
        is ClassRulesData -> throw createException(instanceSet ?: AppParasitics.baseClassLoader, objectName, *templates)
        else -> error("Type [$this] not allowed")
    }

    /**
     * 创建一个异常
     * @param instanceSet 所在 [ClassLoader] or [Class]
     * @param name 实例名称
     * @param content 异常内容
     * @return [Throwable]
     */
    private fun createException(instanceSet: Any?, name: String, vararg content: String): Throwable {
        /**
         * 获取 [Class.getName] 长度的空格数量并使用 "->" 拼接
         * @return [String]
         */
        fun Class<*>.space(): String {
            var space = ""
            for (i in 0..this.name.length) space += " "
            return "$space -> "
        }
        if (content.isEmpty()) return IllegalStateException("Exception content is null")
        val space = when (name) {
            "Class" -> NoClassDefFoundErrorClass.space()
            "Field" -> NoSuchFieldErrorClass.space()
            "Method", "Constructor" -> NoSuchMethodErrorClass.space()
            else -> error("Invalid Exception type")
        }
        var splicing = ""
        content.forEach { if (it.isNotBlank()) splicing += "$space$it\n" }
        val template = "Can't find this $name in [$instanceSet]:\n${splicing}Generated by $TAG"
        return when (name) {
            "Class" -> NoClassDefFoundError(template)
            "Field" -> NoSuchFieldError(template)
            "Method", "Constructor" -> NoSuchMethodError(template)
            else -> error("Invalid Exception type")
        }
    }

    /**
     * 获取当前 [Class] 中存在的 [Member] 数组
     * @return [Array]<[Member]>
     */
    private val Class<*>.existMembers
        get() = runCatching {
            arrayListOf<Member>().apply {
                addAll(declaredFields.toList())
                addAll(declaredMethods.toList())
                addAll(declaredConstructors.toList())
            }.toTypedArray()
        }.onFailure {
            yLoggerW(msg = "Failed to get the declared Members in [$this] because got an exception\n$it")
        }.getOrNull()

    /**
     * 获取当前 [Class] 中存在的 [Field] 数组
     * @return [Array]<[Field]>
     */
    private val Class<*>.existFields
        get() = runCatching { declaredFields }.onFailure {
            yLoggerW(msg = "Failed to get the declared Fields in [$this] because got an exception\n$it")
        }.getOrNull()

    /**
     * 获取当前 [Class] 中存在的 [Method] 数组
     * @return [Array]<[Method]>
     */
    private val Class<*>.existMethods
        get() = runCatching { declaredMethods }.onFailure {
            yLoggerW(msg = "Failed to get the declared Methods in [$this] because got an exception\n$it")
        }.getOrNull()

    /**
     * 获取当前 [Class] 中存在的 [Constructor] 数组
     * @return [Array]<[Constructor]>
     */
    private val Class<*>.existConstructors
        get() = runCatching { declaredConstructors }.onFailure {
            yLoggerW(msg = "Failed to get the declared Constructors in [$this] because got an exception\n$it")
        }.getOrNull()

    /**
     * 判断两个方法、构造方法类型数组是否相等
     *
     * 复制自 [Class] 中的 [Class.arrayContentsEq]
     * @param compare 用于比较的数组
     * @param original 方法、构造方法原始数组
     * @return [Boolean] 是否相等
     * @throws IllegalStateException 如果 [VagueType] 配置不正确
     */
    private fun paramTypesEq(compare: Array<out Any>?, original: Array<out Any>?): Boolean {
        return when {
            (compare == null && original == null) || (compare?.isEmpty() == true && original?.isEmpty() == true) -> true
            (compare == null && original != null) || (compare != null && original == null) || (compare?.size != original?.size) -> false
            else -> {
                if (compare == null || original == null) return false
                if (compare.all { it == VagueType }) error("The number of VagueType must be at least less than the count of paramTypes")
                for (i in compare.indices) if ((compare[i] !== VagueType) && (compare[i] !== original[i])) return false
                true
            }
        }
    }
}
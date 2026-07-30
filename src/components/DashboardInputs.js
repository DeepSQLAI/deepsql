'use client'

import { useState } from 'react'
import { Calendar, ChevronDown, Check } from 'lucide-react'
import styles from './DashboardInputs.module.css'

// Text Input Component
export function TextInput({ input, value, onChange }) {
    return (
        <div className={styles.inputWrapper}>
            {input.label && <label className={styles.inputLabel}>{input.label}</label>}
            <input
                type="text"
                className={styles.input}
                placeholder={input.placeholder || ''}
                value={value || ''}
                onChange={(e) => onChange(input.name, e.target.value)}
            />
        </div>
    )
}

// Number Input Component
export function NumberInput({ input, value, onChange }) {
    return (
        <div className={styles.inputWrapper}>
            {input.label && <label className={styles.inputLabel}>{input.label}</label>}
            <input
                type="number"
                className={styles.input}
                placeholder={input.placeholder || ''}
                value={value || ''}
                onChange={(e) => onChange(input.name, e.target.value)}
                min={input.min}
                max={input.max}
                step={input.step || 1}
            />
        </div>
    )
}

// Date Picker Component (using native HTML5 date input)
export function DatePickerInput({ input, value, onChange }) {
    const handleChange = (e) => {
        onChange(input.name, e.target.value || null)
    }
    
    return (
        <div className={styles.inputWrapper}>
            {input.label && <label className={styles.inputLabel}>{input.label}</label>}
            <div className={styles.datePickerWrapper}>
                <input
                    type="date"
                    className={styles.input}
                    value={value || ''}
                    onChange={handleChange}
                    placeholder={input.placeholder || 'Select date'}
                />
                <Calendar size={18} className={styles.dateIcon} />
            </div>
        </div>
    )
}

// Date Range Picker Component (using native HTML5 date inputs)
// Optional input.maxDays caps the selectable span (e.g. maxDays: 7): the To
// picker can't go past From + maxDays - 1, and moving From forward pulls an
// out-of-range To back inside the cap. Dashboard specs use this to enforce
// "no more than N days" requirements in the UI itself.
export function DateRangePicker({ input, value, onChange }) {
    const startDate = value?.startDate || ''
    const endDate = value?.endDate || ''
    const maxDays = Number(input.maxDays) > 0 ? Math.floor(Number(input.maxDays)) : null

    const shiftDays = (isoDate, days) => {
        const d = new Date(`${isoDate}T00:00:00Z`)
        if (Number.isNaN(d.getTime())) return null
        d.setUTCDate(d.getUTCDate() + days)
        return d.toISOString().slice(0, 10)
    }
    // Latest To allowed for the current From (inclusive span of maxDays days).
    const maxEnd = maxDays && startDate ? shiftDays(startDate, maxDays - 1) : null

    const handleStartDateChange = (e) => {
        const nextStart = e.target.value || null
        let nextEnd = endDate || null
        if (maxDays && nextStart && nextEnd) {
            const cap = shiftDays(nextStart, maxDays - 1)
            if (cap && nextEnd > cap) nextEnd = cap
        }
        onChange(input.name, { startDate: nextStart, endDate: nextEnd })
    }

    const handleEndDateChange = (e) => {
        let nextEnd = e.target.value || null
        if (nextEnd && maxEnd && nextEnd > maxEnd) nextEnd = maxEnd
        onChange(input.name, {
            startDate: startDate || null,
            endDate: nextEnd
        })
    }

    return (
        <div className={styles.inputWrapper}>
            {input.label && <label className={styles.inputLabel}>{input.label}</label>}
            <div className={styles.dateRangeWrapper}>
                <div className={styles.dateRangeInput}>
                    <label className={styles.dateRangeLabel}>From</label>
                    <input
                        type="date"
                        className={styles.input}
                        value={startDate}
                        onChange={handleStartDateChange}
                        placeholder="Start date"
                    />
                </div>
                <div className={styles.dateRangeInput}>
                    <label className={styles.dateRangeLabel}>To</label>
                    <input
                        type="date"
                        className={styles.input}
                        value={endDate}
                        onChange={handleEndDateChange}
                        placeholder="End date"
                        min={startDate || undefined}
                        max={maxEnd || undefined}
                    />
                </div>
            </div>
        </div>
    )
}

// Dropdown/Select Component
export function SelectInput({ input, value, onChange }) {
    const [isOpen, setIsOpen] = useState(false)

    const selectedOption = input.options?.find(opt => 
        (typeof opt === 'object' ? opt.value : opt) === value
    ) || null

    const displayValue = selectedOption
        ? (typeof selectedOption === 'object' ? selectedOption.label : selectedOption)
        : input.placeholder || 'Select option'

    return (
        <div className={styles.inputWrapper}>
            {input.label && <label className={styles.inputLabel}>{input.label}</label>}
            <div className={styles.selectWrapper}>
                <div
                    className={styles.selectInput}
                    onClick={() => setIsOpen(!isOpen)}
                >
                    <span className={styles.selectValue}>{displayValue}</span>
                    <ChevronDown size={18} className={styles.selectIcon} />
                </div>
                {isOpen && (
                    <>
                        <div className={styles.selectOverlay} onClick={() => setIsOpen(false)} />
                        <div className={styles.selectDropdown}>
                            {input.options?.map((option, idx) => {
                                const optionValue = typeof option === 'object' ? option.value : option
                                const optionLabel = typeof option === 'object' ? option.label : option
                                const isSelected = value === optionValue

                                return (
                                    <div
                                        key={idx}
                                        className={`${styles.selectOption} ${isSelected ? styles.selectOptionSelected : ''}`}
                                        onClick={() => {
                                            onChange(input.name, optionValue)
                                            setIsOpen(false)
                                        }}
                                    >
                                        {optionLabel}
                                        {isSelected && <Check size={16} className={styles.checkIcon} />}
                                    </div>
                                )
                            })}
                        </div>
                    </>
                )}
            </div>
        </div>
    )
}

// Checkbox Component
export function CheckboxInput({ input, value, onChange }) {
    return (
        <div className={styles.inputWrapper}>
            <label className={styles.checkboxLabel}>
                <input
                    type="checkbox"
                    className={styles.checkbox}
                    checked={!!value}
                    onChange={(e) => onChange(input.name, e.target.checked)}
                />
                <span>{input.label || input.name}</span>
            </label>
        </div>
    )
}

// Radio Button Component
export function RadioInput({ input, value, onChange }) {
    return (
        <div className={styles.inputWrapper}>
            {input.label && <label className={styles.inputLabel}>{input.label}</label>}
            <div className={styles.radioGroup}>
                {input.options?.map((option, idx) => {
                    const optionValue = typeof option === 'object' ? option.value : option
                    const optionLabel = typeof option === 'object' ? option.label : option

                    return (
                        <label key={idx} className={styles.radioLabel}>
                            <input
                                type="radio"
                                name={input.name}
                                value={optionValue}
                                checked={value === optionValue}
                                onChange={(e) => onChange(input.name, e.target.value)}
                                className={styles.radio}
                            />
                            <span>{optionLabel}</span>
                        </label>
                    )
                })}
            </div>
        </div>
    )
}

// Button Component
export function ButtonInput({ input, onClick }) {
    return (
        <div className={styles.inputWrapper}>
            <button
                className={styles.buttonInput}
                onClick={onClick}
                type="button"
            >
                {input.label || input.name}
            </button>
        </div>
    )
}

// Textarea Component
export function TextareaInput({ input, value, onChange }) {
    return (
        <div className={styles.inputWrapper}>
            {input.label && <label className={styles.inputLabel}>{input.label}</label>}
            <textarea
                className={styles.textarea}
                placeholder={input.placeholder || ''}
                value={value || ''}
                onChange={(e) => onChange(input.name, e.target.value)}
                rows={input.rows || 3}
            />
        </div>
    )
}

// Switch/Toggle Component
export function SwitchInput({ input, value, onChange }) {
    return (
        <div className={styles.inputWrapper}>
            <label className={styles.switchLabel}>
                <span>{input.label || input.name}</span>
                <div className={styles.switchWrapper}>
                    <input
                        type="checkbox"
                        className={styles.switch}
                        checked={!!value}
                        onChange={(e) => onChange(input.name, e.target.checked)}
                    />
                    <span className={styles.switchSlider} />
                </div>
            </label>
        </div>
    )
}

// Main Input Renderer
export function renderInput(input, inputValues, onInputChange, onButtonClick) {
    const value = inputValues[input.name]

    switch (input.type) {
        case 'text':
            return <TextInput key={input.name} input={input} value={value} onChange={onInputChange} />
        
        case 'number':
            return <NumberInput key={input.name} input={input} value={value} onChange={onInputChange} />
        
        case 'date':
            return <DatePickerInput key={input.name} input={input} value={value} onChange={onInputChange} />
        
        case 'dateRange':
            return <DateRangePicker key={input.name} input={input} value={value} onChange={onInputChange} />
        
        case 'select':
        case 'dropdown':
            return <SelectInput key={input.name} input={input} value={value} onChange={onInputChange} />
        
        case 'checkbox':
            return <CheckboxInput key={input.name} input={input} value={value} onChange={onInputChange} />
        
        case 'radio':
            return <RadioInput key={input.name} input={input} value={value} onChange={onInputChange} />
        
        case 'button':
            return <ButtonInput key={input.name} input={input} onClick={onButtonClick} />
        
        case 'textarea':
            return <TextareaInput key={input.name} input={input} value={value} onChange={onInputChange} />
        
        case 'switch':
        case 'toggle':
            return <SwitchInput key={input.name} input={input} value={value} onChange={onInputChange} />
        
        default:
            return <TextInput key={input.name} input={input} value={value} onChange={onInputChange} />
    }
}


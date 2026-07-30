'use client'

import { useState, useRef, useEffect, useCallback } from 'react'
import html2canvas from 'html2canvas'
import styles from './ScreenCapture.module.css'

// Helper function to convert oklch to RGB
// This is a simplified approximation - proper conversion requires complex math
// For html2canvas, we just need to avoid the parsing error, so approximate colors are acceptable
const oklchToRgb = (l, c, h) => {
    // l: lightness (0-1), c: chroma (0-0.4), h: hue (0-360)
    // Normalize values
    const lightness = Math.max(0, Math.min(1, l || 0.5))
    const chroma = Math.max(0, Math.min(0.4, c || 0))
    const hue = h || 0
    
    // Convert to LAB-like space (approximate)
    const a = chroma * Math.cos((hue * Math.PI) / 180)
    const b = chroma * Math.sin((hue * Math.PI) / 180)
    
    // Approximate conversion through simplified transform (OKLab to linear RGB)
    let r = lightness + 0.3963377774 * a + 0.2158037573 * b
    let g = lightness - 0.1055613458 * a - 0.0638541728 * b
    let blue = lightness - 0.0894841775 * a - 1.2914855480 * b
    
    // Apply gamma correction (linear RGB to sRGB)
    const gammaCorrect = (val) => {
        const sign = val < 0 ? -1 : 1
        const abs = Math.abs(val)
        if (abs > 0.0031308) {
            return sign * (1.055 * Math.pow(abs, 1/2.4) - 0.055)
        }
        return sign * (12.92 * abs)
    }
    
    r = Math.max(0, Math.min(1, gammaCorrect(r)))
    g = Math.max(0, Math.min(1, gammaCorrect(g)))
    blue = Math.max(0, Math.min(1, gammaCorrect(blue)))
    
    return `rgb(${Math.round(r * 255)}, ${Math.round(g * 255)}, ${Math.round(blue * 255)})`
}

// Helper function to replace oklch in CSS text
const replaceOklchInCss = (cssText) => {
    // Match oklch() patterns in CSS
    return cssText.replace(/oklch\(([^)]+)\)/gi, (match, params) => {
        try {
            // Parse parameters: l c h or l c h / alpha
            const parts = params.trim().split(/\s+/)
            const l = parseFloat(parts[0]) || 0.5
            const c = parseFloat(parts[1]) || 0
            const h = parseFloat(parts[2]) || 0
            return oklchToRgb(l, c, h)
        } catch (e) {
            // Fallback to gray
            return 'rgb(128, 128, 128)'
        }
    })
}

export default function ScreenCapture({ onCapture, onClose, targetElementId = 'dashboard-container' }) {
    const [isSelecting, setIsSelecting] = useState(false)
    const [selectionStart, setSelectionStart] = useState(null)
    const [selectionEnd, setSelectionEnd] = useState(null)
    const [isCapturing, setIsCapturing] = useState(false)
    const overlayRef = useRef(null)

    const captureSelectedArea = useCallback(async (rect) => {
        // Store original styles to restore later
        const styleBackups = new Map()
        // Store disabled stylesheets for cleanup (must be outside try block for catch block access)
        const disabledStylesheets = []
        // Store processed style elements for restoration
        const processedStyleElements = new Map()
        
        try {
            // Pre-process: Convert computed styles to inline styles to avoid oklch parsing
            // Process only visible elements for better performance
            const allElements = document.querySelectorAll('*:not(script):not(style):not(meta):not(link)')
            const colorProperties = [
                'color', 'background-color', 'background',
                'border-color', 'border-top-color', 'border-right-color',
                'border-bottom-color', 'border-left-color',
                'outline-color', 'text-decoration-color', 'fill', 'stroke'
            ]
            
            // Use requestAnimationFrame to batch style updates
            await new Promise(resolve => {
                let processed = 0
                const batchSize = 50
                
                const processBatch = () => {
                    const end = Math.min(processed + batchSize, allElements.length)
                    for (let i = processed; i < end; i++) {
                        const element = allElements[i]
                        try {
                            // Skip if element is not visible (optimization)
                            const rect = element.getBoundingClientRect()
                            if (rect.width === 0 && rect.height === 0 && element.tagName !== 'BODY' && element.tagName !== 'HTML') {
                                continue
                            }
                            
                            const computedStyle = window.getComputedStyle(element)
                            const inlineStyle = element.style
                            const backup = {}
                            
                            // Backup and apply color properties
                            colorProperties.forEach(prop => {
                                backup[prop] = inlineStyle.getPropertyValue(prop)
                                try {
                                    const computed = computedStyle.getPropertyValue(prop)
                                    // Only convert non-oklch colors to inline styles
                                    // oklch will be handled in the separate pass below
                                    if (computed && computed.trim() && !computed.includes('oklch')) {
                                        // Don't use !important - just set normally to avoid breaking styles
                                        inlineStyle.setProperty(prop, computed)
                                    }
                                } catch (e) {
                                    // Ignore errors
                                }
                            })
                            
                            if (Object.keys(backup).length > 0) {
                                styleBackups.set(element, backup)
                            }
                        } catch (e) {
                            // Ignore errors
                        }
                    }
                    
                    processed = end
                    if (processed < allElements.length) {
                        requestAnimationFrame(processBatch)
                    } else {
                        resolve()
                    }
                }
                
                processBatch()
            })
            
            // Only disable stylesheets that contain oklch to minimize style disruption
            Array.from(document.styleSheets).forEach((sheet, index) => {
                try {
                    // Only disable external stylesheets that might contain oklch
                    if (sheet.href && !sheet.href.startsWith('data:') && !sheet.href.startsWith('blob:')) {
                        let hasOklch = false
                        try {
                            // Check if stylesheet contains oklch
                            if (sheet.cssRules) {
                                // Sample first 20 rules to check for oklch
                                for (let i = 0; i < Math.min(20, sheet.cssRules.length); i++) {
                                    const rule = sheet.cssRules[i]
                                    if (rule.cssText && rule.cssText.includes('oklch')) {
                                        hasOklch = true
                                        break
                                    }
                                }
                            }
                        } catch (e) {
                            // Cross-origin stylesheet - assume it might have oklch and disable it
                            hasOklch = true
                        }
                        
                        if (hasOklch) {
                            const link = document.querySelector(`link[href="${sheet.href}"]`)
                            if (link && !link.disabled) {
                                link.disabled = true
                                disabledStylesheets.push(link)
                            }
                        }
                    }
                } catch (e) {
                    // Ignore stylesheet access errors (cross-origin, etc.)
                }
            })
            
            console.log(`ScreenCapture: Disabled ${disabledStylesheets.length} stylesheets containing oklch`)
            
            // Process all <style> elements in the document and replace oklch with RGB
            const styleElements = document.querySelectorAll('style')
            styleElements.forEach((styleEl, index) => {
                try {
                    const originalText = styleEl.textContent || ''
                    if (originalText.includes('oklch')) {
                        // Store original text for restoration
                        processedStyleElements.set(styleEl, originalText)
                        // Replace oklch with RGB equivalents
                        styleEl.textContent = replaceOklchInCss(originalText)
                        console.log(`ScreenCapture: Processed style element ${index}, replaced oklch values`)
                    }
                } catch (e) {
                    console.warn(`ScreenCapture: Error processing style element ${index}`, e)
                }
            })
            
            console.log(`ScreenCapture: Processed ${processedStyleElements.size} style elements containing oklch`)
            
            // Convert oklch in computed styles to RGB - only for elements that have oklch
            // Store backups so we can restore them
            const oklchBackups = new Map()
            const allElementsForOklch = document.querySelectorAll('*')
            allElementsForOklch.forEach(element => {
                try {
                    const computed = window.getComputedStyle(element)
                    const inline = element.style
                    const backup = {}
                    
                    // Check all color properties for oklch
                    ['color', 'background-color', 'background', 'border-color', 'border-top-color', 
                     'border-right-color', 'border-bottom-color', 'border-left-color',
                     'outline-color', 'text-decoration-color', 'fill', 'stroke', 'box-shadow'].forEach(prop => {
                        const value = computed.getPropertyValue(prop)
                        if (value && value.includes('oklch')) {
                            // Backup original inline style
                            backup[prop] = inline.getPropertyValue(prop)
                            
                            // Replace oklch with RGB using helper function
                            try {
                                const rgbValue = replaceOklchInCss(value)
                                inline.setProperty(prop, rgbValue)
                            } catch (e) {
                                // Fallback to neutral gray if conversion fails
                                inline.setProperty(prop, 'rgb(128, 128, 128)')
                            }
                        }
                    })
                    
                    if (Object.keys(backup).length > 0) {
                        oklchBackups.set(element, backup)
                    }
                } catch (e) {
                    // Ignore errors
                }
            })
            
            // Store oklch backups in styleBackups for restoration
            oklchBackups.forEach((backup, element) => {
                if (!styleBackups.has(element)) {
                    styleBackups.set(element, {})
                }
                Object.assign(styleBackups.get(element), backup)
            })
            
            // Now capture with html2canvas - it will use inline styles instead of parsing CSS
            // Add a delay to ensure stylesheet changes take effect and DOM is stable
            // Use requestAnimationFrame to ensure we capture after a paint cycle
            await new Promise(resolve => {
                requestAnimationFrame(() => {
                    requestAnimationFrame(() => {
                        setTimeout(resolve, 100)
                    })
                })
            })
            
            // Capture the entire body to ensure we get all content
            // Container-based capture was causing coordinate issues
            console.log('ScreenCapture: Capturing document.body', {
                bodyWidth: document.body.offsetWidth,
                bodyHeight: document.body.offsetHeight,
                scrollWidth: document.body.scrollWidth,
                scrollHeight: document.body.scrollHeight
            })
            
            const canvas = await html2canvas(document.body, {
                useCORS: false, // Disable CORS to avoid tainted canvas issues
                logging: false,
                scale: 1, // Use full scale for better quality
                backgroundColor: '#ffffff',
                windowWidth: window.innerWidth,
                windowHeight: window.innerHeight,
                foreignObjectRendering: false, // Disable foreignObject rendering to avoid CSS parsing issues
                ignoreElements: (element) => {
                    // Ignore the screen capture overlay and selection box
                    return element.id === 'html2canvas-oklch-override' ||
                           element.classList?.contains('snipperOverlay') ||
                           element.classList?.contains('selectionBox') ||
                           element.tagName === 'SCRIPT' ||
                           element.tagName === 'STYLE'
                },
                onclone: (clonedDoc) => {
                    // Process the cloned document more aggressively
                    // Replace oklch in all style elements
                    const styleTags = clonedDoc.querySelectorAll('style')
                    styleTags.forEach(style => {
                        if (style.textContent && style.textContent.includes('oklch')) {
                            try {
                                style.textContent = replaceOklchInCss(style.textContent)
                            } catch (e) {
                                // If replacement fails, remove the style tag
                                console.warn('ScreenCapture: Error processing style in cloned doc, removing', e)
                                style.remove()
                            }
                        }
                    })
                    
                    // Process inline styles on all elements in the cloned document
                    const allClonedElements = clonedDoc.querySelectorAll('*')
                    allClonedElements.forEach(element => {
                        try {
                            const inlineStyle = element.getAttribute('style')
                            if (inlineStyle && inlineStyle.includes('oklch')) {
                                element.setAttribute('style', replaceOklchInCss(inlineStyle))
                            }
                        } catch (e) {
                            // Ignore errors
                        }
                    })
                }
            })
            
            console.log('ScreenCapture: Canvas created', {
                canvasWidth: canvas.width,
                canvasHeight: canvas.height,
                windowWidth: window.innerWidth,
                windowHeight: window.innerHeight
            })
            
            // Validate canvas has content
            if (!canvas || canvas.width === 0 || canvas.height === 0) {
                throw new Error(`Canvas is invalid: width=${canvas?.width}, height=${canvas?.height}`)
            }
            
            // Check if canvas has actual content (not just white)
            try {
                const ctx = canvas.getContext('2d')
                const imageData = ctx.getImageData(0, 0, Math.min(100, canvas.width), Math.min(100, canvas.height))
                const pixels = imageData.data
                let hasContent = false
                // Check a sample of pixels - if they're not all white (255,255,255), we have content
                for (let i = 0; i < pixels.length; i += 16) { // Sample every 4th pixel (RGBA)
                    const r = pixels[i]
                    const g = pixels[i + 1]
                    const b = pixels[i + 2]
                    // Not pure white
                    if (!(r === 255 && g === 255 && b === 255)) {
                        hasContent = true
                        break
                    }
                }
                if (!hasContent && canvas.width > 100 && canvas.height > 100) {
                    console.warn('ScreenCapture: Canvas appears to be empty/white - might indicate rendering issue')
                }
            } catch (e) {
                console.warn('ScreenCapture: Could not validate canvas content', e)
            }
            
            // Restore original styles - this includes both the original style backups and oklch conversions
            styleBackups.forEach((backup, element) => {
                try {
                    Object.keys(backup).forEach(prop => {
                        const originalValue = backup[prop]
                        if (originalValue && originalValue.trim() !== '') {
                            // Restore the original value
                            element.style.setProperty(prop, originalValue)
                        } else {
                            // Remove the property if it was empty originally
                            element.style.removeProperty(prop)
                        }
                    })
                } catch (e) {
                    // Ignore restoration errors
                    console.warn('ScreenCapture: Error restoring style', e)
                }
            })
            styleBackups.clear()
            
            // Restore processed style elements
            processedStyleElements.forEach((originalText, styleEl) => {
                try {
                    styleEl.textContent = originalText
                } catch (e) {
                    console.warn('ScreenCapture: Error restoring style element', e)
                }
            })
            processedStyleElements.clear()
            
            // Re-enable disabled stylesheets (safe to call even if array is empty)
            if (disabledStylesheets && disabledStylesheets.length > 0) {
                disabledStylesheets.forEach(link => {
                    try {
                        if (link && link.disabled !== undefined) {
                            link.disabled = false
                        }
                    } catch (e) {
                        // Ignore errors when re-enabling stylesheets
                        console.warn('ScreenCapture: Error re-enabling stylesheet', e)
                    }
                })
            }

            // Create a temporary canvas to crop the selected area
            const tempCanvas = document.createElement('canvas')
            tempCanvas.width = Math.max(1, rect.width)
            tempCanvas.height = Math.max(1, rect.height)
            const ctx = tempCanvas.getContext('2d')

            // Fill with white background first
            ctx.fillStyle = '#ffffff'
            ctx.fillRect(0, 0, tempCanvas.width, tempCanvas.height)

            // Calculate the crop coordinates relative to the canvas
            // Canvas is scaled version of the viewport
            const scaleX = canvas.width / window.innerWidth
            const scaleY = canvas.height / window.innerHeight

            // Convert viewport coordinates to canvas coordinates
            const sourceX = Math.max(0, Math.floor(rect.left * scaleX))
            const sourceY = Math.max(0, Math.floor(rect.top * scaleY))
            const sourceWidth = Math.min(Math.ceil(rect.width * scaleX), canvas.width - sourceX)
            const sourceHeight = Math.min(Math.ceil(rect.height * scaleY), canvas.height - sourceY)
            
            console.log('ScreenCapture: Crop calculation', {
                viewportRect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
                canvasSize: { width: canvas.width, height: canvas.height },
                scale: { x: scaleX, y: scaleY },
                source: { x: sourceX, y: sourceY, width: sourceWidth, height: sourceHeight },
                tempCanvas: { width: tempCanvas.width, height: tempCanvas.height }
            })

            // Only draw if we have valid dimensions
            if (sourceWidth > 0 && sourceHeight > 0 && tempCanvas.width > 0 && tempCanvas.height > 0 && 
                sourceX < canvas.width && sourceY < canvas.height) {
                console.log('ScreenCapture: Drawing cropped image to temp canvas')
                ctx.drawImage(
                    canvas,
                    sourceX,
                    sourceY,
                    sourceWidth,
                    sourceHeight,
                    0,
                    0,
                    tempCanvas.width,
                    tempCanvas.height
                )
                console.log('ScreenCapture: Image drawn successfully')
            } else {
                console.error('ScreenCapture: Invalid crop dimensions - cannot draw', {
                    sourceX,
                    sourceY,
                    sourceWidth,
                    sourceHeight,
                    canvasSize: { width: canvas.width, height: canvas.height },
                    tempCanvasSize: { width: tempCanvas.width, height: tempCanvas.height },
                    isValid: sourceWidth > 0 && sourceHeight > 0 && 
                            tempCanvas.width > 0 && tempCanvas.height > 0 &&
                            sourceX < canvas.width && sourceY < canvas.height
                })
                // Fallback: try to capture the entire canvas as a last resort
                ctx.drawImage(canvas, 0, 0, canvas.width, canvas.height, 0, 0, tempCanvas.width, tempCanvas.height)
                console.log('ScreenCapture: Used fallback - captured entire canvas')
            }

            // Validate cropped canvas has content
            try {
                const croppedCtx = tempCanvas.getContext('2d')
                const croppedImageData = croppedCtx.getImageData(0, 0, tempCanvas.width, tempCanvas.height)
                const croppedPixels = croppedImageData.data
                let hasCroppedContent = false
                // Check if cropped area has non-white content
                for (let i = 0; i < Math.min(1000, croppedPixels.length); i += 16) {
                    const r = croppedPixels[i]
                    const g = croppedPixels[i + 1]
                    const b = croppedPixels[i + 2]
                    if (!(r === 255 && g === 255 && b === 255)) {
                        hasCroppedContent = true
                        break
                    }
                }
                if (!hasCroppedContent) {
                    console.warn('ScreenCapture: Cropped canvas appears to be empty/white', {
                        tempCanvasWidth: tempCanvas.width,
                        tempCanvasHeight: tempCanvas.height,
                        sourceX,
                        sourceY,
                        sourceWidth,
                        sourceHeight,
                        canvasWidth: canvas.width,
                        canvasHeight: canvas.height
                    })
                }
            } catch (e) {
                console.warn('ScreenCapture: Could not validate cropped content', e)
            }
            
            const imageData = tempCanvas.toDataURL('image/png')
            console.log('ScreenCapture: Image captured successfully', {
                width: tempCanvas.width,
                height: tempCanvas.height,
                dataLength: imageData.length,
                dataPrefix: imageData.substring(0, 50),
                isValid: imageData.startsWith('data:image/png'),
                onCaptureType: typeof onCapture,
                onCaptureExists: !!onCapture
            })
            
            if (!imageData || imageData.length < 100) {
                console.error('ScreenCapture: Image data is too small or invalid')
                alert('Failed to capture image. The selected area might be too small.')
                onClose()
                return
            }
            
            // Additional validation: check if image data is just a white rectangle
            // A pure white PNG will have a very small data size or consistent pattern
            // This is a heuristic check - actual white images will pass but that's OK
            if (imageData.length < 500 && tempCanvas.width > 50 && tempCanvas.height > 50) {
                console.warn('ScreenCapture: Image data seems unusually small for the canvas size - might be empty')
                
                // Try simpler capture without style processing if we got an empty result
                try {
                    console.log('ScreenCapture: Attempting simpler capture without style preprocessing...')
                    const simpleCanvas = await html2canvas(document.body, {
                        useCORS: false,
                        logging: false,
                        scale: 1,
                        backgroundColor: '#ffffff',
                        windowWidth: window.innerWidth,
                        windowHeight: window.innerHeight,
                        ignoreElements: (element) => {
                            return element.classList?.contains('snipperOverlay') ||
                                   element.classList?.contains('selectionBox')
                        }
                    })
                    
                    // Try crop again with simple canvas
                    if (simpleCanvas && simpleCanvas.width > 0 && simpleCanvas.height > 0) {
                        const simpleScaleX = simpleCanvas.width / window.innerWidth
                        const simpleScaleY = simpleCanvas.height / window.innerHeight
                        const simpleSourceX = Math.max(0, Math.floor(rect.left * simpleScaleX))
                        const simpleSourceY = Math.max(0, Math.floor(rect.top * simpleScaleY))
                        const simpleSourceWidth = Math.min(Math.ceil(rect.width * simpleScaleX), simpleCanvas.width - simpleSourceX)
                        const simpleSourceHeight = Math.min(Math.ceil(rect.height * simpleScaleY), simpleCanvas.height - simpleSourceY)
                        
                        if (simpleSourceWidth > 0 && simpleSourceHeight > 0) {
                            const simpleTempCanvas = document.createElement('canvas')
                            simpleTempCanvas.width = Math.max(1, rect.width)
                            simpleTempCanvas.height = Math.max(1, rect.height)
                            const simpleCtx = simpleTempCanvas.getContext('2d')
                            
                            simpleCtx.fillStyle = '#ffffff'
                            simpleCtx.fillRect(0, 0, simpleTempCanvas.width, simpleTempCanvas.height)
                            simpleCtx.drawImage(
                                simpleCanvas,
                                simpleSourceX,
                                simpleSourceY,
                                simpleSourceWidth,
                                simpleSourceHeight,
                                0,
                                0,
                                simpleTempCanvas.width,
                                simpleTempCanvas.height
                            )
                            
                            const simpleImageData = simpleTempCanvas.toDataURL('image/png')
                            if (simpleImageData && simpleImageData.length > imageData.length) {
                                console.log('ScreenCapture: Simple capture produced better result')
                                // Use the simpler capture result
                                if (typeof onCapture === 'function') {
                                    onCapture(simpleImageData)
                                    setIsCapturing(false)
                                    return
                                }
                            }
                        }
                    }
                } catch (simpleError) {
                    console.warn('ScreenCapture: Simple capture fallback also failed', simpleError)
                }
            }
            
            if (typeof onCapture !== 'function') {
                console.error('ScreenCapture: onCapture is not a function', { onCapture })
                alert('Internal error: capture callback is not available')
                onClose()
                return
            }
            
            console.log('ScreenCapture: Calling onCapture callback with image data')
            try {
                onCapture(imageData)
                console.log('ScreenCapture: onCapture callback executed successfully')
                setIsCapturing(false)
            } catch (callbackError) {
                console.error('ScreenCapture: Error in onCapture callback', callbackError)
                setIsCapturing(false)
                alert('Failed to process captured image. Please try again.')
                onClose()
            }
        } catch (error) {
            console.error('Error capturing screen:', error)
            console.error('Error details:', {
                message: error.message,
                stack: error.stack,
                name: error.name,
                rect: rect
            })
            
            // Clean up in case of error
            styleBackups.forEach((backup, element) => {
                try {
                    Object.keys(backup).forEach(prop => {
                        if (backup[prop]) {
                            element.style.setProperty(prop, backup[prop])
                        } else {
                            element.style.removeProperty(prop)
                        }
                    })
                } catch (e) {
                    // Ignore restoration errors
                }
            })
            styleBackups.clear()
            
            // Restore processed style elements
            processedStyleElements.forEach((originalText, styleEl) => {
                try {
                    styleEl.textContent = originalText
                } catch (e) {
                    // Ignore restoration errors
                }
            })
            processedStyleElements.clear()
            
            // Re-enable disabled stylesheets (safe to call even if array is empty)
            if (disabledStylesheets && disabledStylesheets.length > 0) {
                disabledStylesheets.forEach(link => {
                    try {
                        if (link && link.disabled !== undefined) {
                            link.disabled = false
                        }
                    } catch (e) {
                        // Ignore errors when re-enabling stylesheets
                        console.warn('ScreenCapture: Error re-enabling stylesheet', e)
                    }
                })
            }
            
            // Try simpler fallback capture methods
            const errorMessage = error.message || error.toString() || ''
            const isOklchError = errorMessage.includes('oklch') || errorMessage.includes('color')
            const isParseError = errorMessage.includes('parse') || errorMessage.includes('CSS') || errorMessage.includes('syntax')
            
            // First, try capturing the element at the selected coordinates directly
            if (isOklchError || isParseError || true) { // Try fallback for any error
                try {
                    console.log('Attempting direct element capture at coordinates...')
                    // Find the element at the center of the selection
                    const centerX = rect.left + rect.width / 2
                    const centerY = rect.top + rect.height / 2
                    const elementAtPoint = document.elementFromPoint(centerX, centerY)
                    
                    if (elementAtPoint) {
                        // Find the closest scrollable container or main content area
                        let targetElement = elementAtPoint
                        let parent = elementAtPoint.parentElement
                        let depth = 0
                        while (parent && depth < 10) {
                            const rect = parent.getBoundingClientRect()
                            if (rect.width > 200 && rect.height > 200) {
                                targetElement = parent
                                break
                            }
                            parent = parent.parentElement
                            depth++
                        }
                        
                        console.log('Capturing element:', {
                            tagName: targetElement.tagName,
                            className: targetElement.className,
                            id: targetElement.id
                        })
                        
                        const simpleCanvas = await html2canvas(targetElement, {
                            useCORS: false,
                            logging: false,
                            scale: 0.8,
                            backgroundColor: '#ffffff',
                            allowTaint: true,
                            foreignObjectRendering: false,
                            ignoreElements: (el) => {
                                return el.classList?.contains('snipperOverlay') || 
                                       el.classList?.contains('selectionBox')
                            }
                        })
                        
                        // Calculate crop area relative to the element
                        const elementRect = targetElement.getBoundingClientRect()
                        const relativeLeft = rect.left - elementRect.left
                        const relativeTop = rect.top - elementRect.top
                        const scaleX = simpleCanvas.width / elementRect.width
                        const scaleY = simpleCanvas.height / elementRect.height
                        
                        const cropCanvas = document.createElement('canvas')
                        cropCanvas.width = Math.max(1, rect.width)
                        cropCanvas.height = Math.max(1, rect.height)
                        const cropCtx = cropCanvas.getContext('2d')
                        
                        cropCtx.fillStyle = '#ffffff'
                        cropCtx.fillRect(0, 0, cropCanvas.width, cropCanvas.height)
                        
                        const sourceX = Math.max(0, relativeLeft * scaleX)
                        const sourceY = Math.max(0, relativeTop * scaleY)
                        const sourceW = Math.min(rect.width * scaleX, simpleCanvas.width - sourceX)
                        const sourceH = Math.min(rect.height * scaleY, simpleCanvas.height - sourceY)
                        
                        if (sourceW > 0 && sourceH > 0) {
                            cropCtx.drawImage(simpleCanvas, sourceX, sourceY, sourceW, sourceH, 0, 0, cropCanvas.width, cropCanvas.height)
                            const imageData = cropCanvas.toDataURL('image/png')
                            
                            if (imageData && imageData.length > 100 && typeof onCapture === 'function') {
                                console.log('Direct element capture successful')
                                onCapture(imageData)
                                setIsCapturing(false)
                                return
                            }
                        }
                    }
                } catch (directError) {
                    console.error('Direct element capture failed:', directError)
                }
            }
            
            // If direct capture failed, try container-based fallback
            if (isOklchError || isParseError) {
                try {
                    console.log('Retrying with simplified capture (no CSS processing)...')
                    // Try capturing with minimal options to avoid CSS parsing issues
                    const container = document.querySelector('[data-dashboard-container], main, #root, .app-container') || document.body
                    
                    const fallbackCanvas = await html2canvas(container, {
                        useCORS: false,
                        logging: true,
                        scale: 0.5, // Lower scale for faster processing
                        backgroundColor: '#ffffff',
                        allowTaint: true,
                        foreignObjectRendering: false,
                        removeContainer: false,
                        ignoreElements: (el) => {
                            // Ignore the screen capture overlay itself
                            return el.classList?.contains('snipperOverlay') || 
                                   el.classList?.contains('selectionBox') ||
                                   el.id === 'html2canvas-oklch-override'
                        }
                    })
                    
                    console.log('Fallback canvas created:', {
                        width: fallbackCanvas.width,
                        height: fallbackCanvas.height,
                        containerWidth: container.offsetWidth,
                        containerHeight: container.offsetHeight
                    })
                    
                    // Crop the selected area
                    const tempCanvas = document.createElement('canvas')
                    tempCanvas.width = Math.max(1, rect.width)
                    tempCanvas.height = Math.max(1, rect.height)
                    const ctx = tempCanvas.getContext('2d')
                    
                    // Fill with white background
                    ctx.fillStyle = '#ffffff'
                    ctx.fillRect(0, 0, tempCanvas.width, tempCanvas.height)
                    
                    const scaleX = fallbackCanvas.width / container.offsetWidth
                    const scaleY = fallbackCanvas.height / container.offsetHeight
                    
                    // Adjust rect coordinates relative to container
                    const containerRect = container.getBoundingClientRect()
                    const adjustedLeft = rect.left - containerRect.left
                    const adjustedTop = rect.top - containerRect.top
                    
                    const sourceX = Math.max(0, adjustedLeft * scaleX)
                    const sourceY = Math.max(0, adjustedTop * scaleY)
                    const sourceWidth = Math.min(rect.width * scaleX, fallbackCanvas.width - sourceX)
                    const sourceHeight = Math.min(rect.height * scaleY, fallbackCanvas.height - sourceY)
                    
                    console.log('Fallback crop params:', {
                        sourceX, sourceY, sourceWidth, sourceHeight,
                        destWidth: tempCanvas.width, destHeight: tempCanvas.height
                    })
                    
                    if (sourceWidth > 0 && sourceHeight > 0) {
                        ctx.drawImage(
                            fallbackCanvas,
                            sourceX,
                            sourceY,
                            sourceWidth,
                            sourceHeight,
                            0,
                            0,
                            tempCanvas.width,
                            tempCanvas.height
                        )
                        
                        const imageData = tempCanvas.toDataURL('image/png')
                        console.log('ScreenCapture: Fallback capture successful', {
                            imageLength: imageData.length,
                            isValid: imageData.startsWith('data:image')
                        })
                        
                        if (typeof onCapture === 'function' && imageData && imageData.length > 100) {
                            try {
                                onCapture(imageData)
                                console.log('ScreenCapture: Fallback onCapture callback executed successfully')
                                setIsCapturing(false)
                                return
                            } catch (callbackError) {
                                console.error('ScreenCapture: Error in fallback onCapture callback', callbackError)
                                setIsCapturing(false)
                            }
                        } else {
                            console.error('ScreenCapture: Invalid fallback image data or callback')
                        }
                    } else {
                        console.error('ScreenCapture: Invalid fallback crop dimensions')
                    }
                } catch (fallbackError) {
                    console.error('Fallback capture also failed:', fallbackError)
                    console.error('Fallback error details:', {
                        message: fallbackError.message,
                        stack: fallbackError.stack
                    })
                }
            }
            
            // If we get here, all capture methods failed
            setIsCapturing(false)
            const errorMsg = error.message || error.toString() || 'Unknown error'
            console.error('ScreenCapture: All capture methods failed. Final error:', errorMsg)
            
            // Provide a more helpful error message
            let userMessage = 'Failed to capture screen. '
            if (errorMsg.includes('oklch') || errorMsg.includes('color')) {
                userMessage += 'There was an issue with CSS color parsing. '
            } else if (errorMsg.includes('CORS') || errorMsg.includes('tainted')) {
                userMessage += 'There was a cross-origin resource issue. '
            } else if (errorMsg.includes('canvas') || errorMsg.includes('drawImage')) {
                userMessage += 'There was an issue with image processing. '
            }
            userMessage += 'Please try:\n1. Selecting a smaller area\n2. Refreshing the page\n3. Using drag & drop or paste to attach images instead'
            
            alert(userMessage)
            onClose()
        }
    }, [onCapture, onClose])

    useEffect(() => {
        const getSelectionRect = () => {
            if (!selectionStart || !selectionEnd) return null

            const left = Math.min(selectionStart.x, selectionEnd.x)
            const top = Math.min(selectionStart.y, selectionEnd.y)
            const width = Math.abs(selectionEnd.x - selectionStart.x)
            const height = Math.abs(selectionEnd.y - selectionStart.y)

            return { left, top, width, height }
        }

        // Add event listeners for selection
        const handleMouseDown = (e) => {
            // Start selection anywhere on the page
            e.preventDefault()
            e.stopPropagation()
            console.log('ScreenCapture: Mouse down, starting selection', { x: e.clientX, y: e.clientY })
            setIsSelecting(true)
            setSelectionStart({ x: e.clientX, y: e.clientY })
            setSelectionEnd({ x: e.clientX, y: e.clientY })
        }

        const handleMouseMove = (e) => {
            if (isSelecting && selectionStart) {
                setSelectionEnd({ x: e.clientX, y: e.clientY })
            }
        }

        const handleMouseUp = async (e) => {
            if (isSelecting && selectionStart && selectionEnd) {
                const rect = getSelectionRect()
                console.log('ScreenCapture: Mouse up, selection complete', {
                    rect,
                    width: rect?.width,
                    height: rect?.height,
                    isLargeEnough: rect && rect.width > 10 && rect.height > 10
                })
                if (rect && rect.width > 5 && rect.height > 5) {
                    // Only capture if selection is large enough (reduced from 10 to 5)
                    console.log('ScreenCapture: Starting capture for selected area')
                    setIsCapturing(true)
                    try {
                        await captureSelectedArea(rect)
                    } catch (error) {
                        console.error('ScreenCapture: Error during capture', error)
                        setIsCapturing(false)
                        alert('Failed to capture screen. Please try again.')
                    }
                } else {
                    console.warn('ScreenCapture: Selection too small, ignoring', { rect })
                }
                setIsSelecting(false)
                setSelectionStart(null)
                setSelectionEnd(null)
            } else {
                console.log('ScreenCapture: Mouse up but no valid selection', {
                    isSelecting,
                    hasStart: !!selectionStart,
                    hasEnd: !!selectionEnd
                })
            }
        }

        const handleKeyDown = (e) => {
            // Escape key cancels capture
            if (e.key === 'Escape') {
                onClose()
            }
        }

        document.addEventListener('mousedown', handleMouseDown)
        document.addEventListener('mousemove', handleMouseMove)
        document.addEventListener('mouseup', handleMouseUp)
        document.addEventListener('keydown', handleKeyDown)

        return () => {
            document.removeEventListener('mousedown', handleMouseDown)
            document.removeEventListener('mousemove', handleMouseMove)
            document.removeEventListener('mouseup', handleMouseUp)
            document.removeEventListener('keydown', handleKeyDown)
        }
    }, [isSelecting, selectionStart, selectionEnd, onClose, captureSelectedArea])

    const getSelectionRect = () => {
        if (!selectionStart || !selectionEnd) return null

        const left = Math.min(selectionStart.x, selectionEnd.x)
        const top = Math.min(selectionStart.y, selectionEnd.y)
        const width = Math.abs(selectionEnd.x - selectionStart.x)
        const height = Math.abs(selectionEnd.y - selectionStart.y)

        return { left, top, width, height }
    }

    const selectionRect = getSelectionRect()

    return (
        <div 
            ref={overlayRef}
            className={styles.snipperOverlay}
            style={{ cursor: isSelecting ? 'crosshair' : 'crosshair' }}
        >
            {selectionRect && (
                <div
                    className={styles.selectionBox}
                    style={{
                        left: `${selectionRect.left}px`,
                        top: `${selectionRect.top}px`,
                        width: `${selectionRect.width}px`,
                        height: `${selectionRect.height}px`
                    }}
                >
                    <div className={styles.selectionInfo}>
                        {Math.round(selectionRect.width)} × {Math.round(selectionRect.height)}
                    </div>
                </div>
            )}
            <div className={styles.instructions}>
                <div className={styles.instructionBox}>
                    {isCapturing ? (
                        <>
                            <p>Capturing screenshot...</p>
                            <p className={styles.instructionHint}>Please wait</p>
                        </>
                    ) : (
                        <>
                            <p>Click and drag to select an area</p>
                            <p className={styles.instructionHint}>Press ESC to cancel</p>
                        </>
                    )}
                </div>
            </div>
        </div>
    )
}
